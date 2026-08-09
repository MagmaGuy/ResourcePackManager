package com.magmaguy.resourcepackmanager.mixer.engine.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * One traversal of a staging tree, plus the content digest RSPM derives from it.
 *
 * <h2>Why the traversal is its own type</h2>
 * <p>{@code Files.walk(root).filter(Files::isRegularFile)} looks free but is not: the walk already
 * reads each directory's entries <i>with</i> their attributes, and then {@code isRegularFile}
 * throws that away and issues a fresh {@code stat} per entry. On a 10k-file pack on NTFS that is
 * 10k redundant syscalls. {@link #sortedRegularFiles} uses {@code walkFileTree}, which hands the
 * visitor the {@link BasicFileAttributes} the directory read already produced, so the type and the
 * size come for free.</p>
 *
 * <h2>Why the digest is parallel</h2>
 * <p>{@code BedrockConversion} hashes every staged byte to derive the Bedrock pack version, so an
 * unchanged pack keeps its {@code (uuid, version)} cache key and clients don't redownload. That
 * hash is inherently sequential — but the <i>reads</i> feeding it are not. {@link #sha256Hex}
 * prefetches file contents on a bounded pool and feeds them to a single {@link MessageDigest} in
 * the same sorted order as before, so the resulting hex string is byte-for-byte what the old serial
 * loop produced while the I/O overlaps.</p>
 */
public final class PackFileIndex {

    /** A staged regular file: absolute path, pack-relative name, and size, all from one stat. */
    public record PackFile(Path path, String relativePath, long size) {
    }

    /**
     * Files this big are streamed on the consuming thread rather than buffered whole, so a stray
     * huge asset cannot make the prefetch window balloon the heap.
     */
    private static final long PREFETCH_SIZE_LIMIT = 16L * 1024 * 1024;
    /** Hard cap for all prefetched file bodies retained by one digest operation. */
    private static final long IN_FLIGHT_BYTE_BUDGET = 32L * 1024 * 1024;
    private static final int MAX_PENDING_READS = 8;
    private static final int MAX_WORKERS = 4;
    private static final int READ_CHUNK = 64 * 1024;

    private static final AtomicInteger POOL_INDEX = new AtomicInteger();

    private PackFileIndex() {
    }

    /**
     * Every regular file under {@code root}, sorted by pack-relative path with {@code /}
     * separators — the same ordering the previous {@code Files.walk(...).sorted(...)} calls used,
     * so callers that depend on entry order (the archive writers, the content digest) are
     * unaffected.
     */
    public static List<PackFile> sortedRegularFiles(Path root) throws IOException {
        List<PackFile> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (attributes.isRegularFile()) {
                    files.add(new PackFile(file, relativize(root, file), attributes.size()));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure) {
                // Matches the old Files.walk behaviour of simply not listing what cannot be read.
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Comparator.comparing(PackFile::relativePath));
        return files;
    }

    public static String relativize(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    /**
     * SHA-256 over {@code relativePath, 0x00, content, 0x00} for every file in order — identical to
     * the serial digest loop it replaces, but with reads overlapped across a bounded pool.
     *
     * @return the digest as lowercase hex
     * @throws CancellationException if cancellation is requested mid-digest
     */
    public static String sha256Hex(List<PackFile> files, BooleanSupplier cancellationRequested)
            throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        updateDigest(digest, files, cancellationRequested);
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Feeds {@code relativePath, 0x00, content, 0x00} for every file, in order, into an existing
     * digest. Callers that mix additional material into the same digest (the proxy merger appends
     * {@code min_engine_version}) use this so their token derivation is preserved exactly.
     */
    public static void updateDigest(MessageDigest digest, List<PackFile> files,
                                    BooleanSupplier cancellationRequested) throws IOException {
        if (files.isEmpty()) return;
        int workerCount = Math.min(MAX_WORKERS, files.size());
        ExecutorService pool = Executors.newFixedThreadPool(workerCount, threadFactory());
        try {
            ArrayDeque<PendingRead> inFlight = new ArrayDeque<>(MAX_PENDING_READS);
            int next = 0;
            long reservedBytes = 0L;

            while (next < files.size() || !inFlight.isEmpty()) {
                checkCancelled(cancellationRequested);
                while (next < files.size() && inFlight.size() < MAX_PENDING_READS) {
                    PackFile candidate = files.get(next);
                    long reservation = prefetchReservation(candidate);
                    if (reservation > 0L
                            && reservedBytes + reservation > IN_FLIGHT_BYTE_BUDGET
                            && !inFlight.isEmpty()) {
                        break;
                    }
                    Future<PrefetchedFile> future = reservation == 0L
                            ? null
                            : pool.submit(readTask(candidate, cancellationRequested));
                    inFlight.add(new PendingRead(candidate, future, reservation));
                    reservedBytes += reservation;
                    next++;
                }

                PendingRead pending = inFlight.removeFirst();
                PackFile file = pending.file();
                digest.update(file.relativePath().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);

                PrefetchedFile prefetched = await(pending.future());
                reservedBytes -= pending.reservedBytes();
                if (prefetched != null) {
                    digest.update(prefetched.bytes(), 0, prefetched.length());
                } else {
                    // Oversized file: stream it here instead of holding it in the window.
                    byte[] buffer = new byte[READ_CHUNK];
                    try (InputStream input = Files.newInputStream(file.path())) {
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            checkCancelled(cancellationRequested);
                            digest.update(buffer, 0, read);
                        }
                    }
                }
                digest.update((byte) 0);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static long prefetchReservation(PackFile file) {
        if (file.size() < 0L || file.size() > PREFETCH_SIZE_LIMIT) return 0L;
        return Math.max(1L, file.size());
    }

    private static Callable<PrefetchedFile> readTask(
            PackFile file, BooleanSupplier cancellationRequested) {
        return () -> {
            checkCancelled(cancellationRequested);
            if (file.size() < 0L || file.size() > PREFETCH_SIZE_LIMIT) return null;
            int indexedSize = Math.toIntExact(file.size());
            byte[] bytes = new byte[indexedSize];
            int offset = 0;
            try (InputStream input = Files.newInputStream(file.path())) {
                while (offset < bytes.length) {
                    checkCancelled(cancellationRequested);
                    int read = input.read(bytes, offset, Math.min(READ_CHUNK, bytes.length - offset));
                    if (read < 0) return new PrefetchedFile(bytes, offset);
                    if (read == 0) continue;
                    offset += read;
                }
                // If the file grew since indexing, avoid an unbudgeted expansion.
                // The ordered consumer will stream the current file instead.
                checkCancelled(cancellationRequested);
                if (input.read() >= 0) return null;
            }
            return new PrefetchedFile(bytes, bytes.length);
        };
    }

    private static PrefetchedFile await(Future<PrefetchedFile> future) throws IOException {
        if (future == null) return null;
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Resource pack content digest cancelled");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof CancellationException cancelled) throw cancelled;
            if (cause instanceof IOException ioException) throw ioException;
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IOException("Failed to read staged pack file", cause);
        }
    }

    private static java.util.concurrent.ThreadFactory threadFactory() {
        int poolId = POOL_INDEX.incrementAndGet();
        AtomicInteger threadId = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "RSPM-digest-" + poolId + "-" + threadId.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        };
    }

    private static void checkCancelled(BooleanSupplier cancellationRequested) {
        Cancellation.check(cancellationRequested, "Resource pack content digest cancelled");
    }

    private record PrefetchedFile(byte[] bytes, int length) {
    }

    private record PendingRead(PackFile file, Future<PrefetchedFile> future, long reservedBytes) {
    }
}
