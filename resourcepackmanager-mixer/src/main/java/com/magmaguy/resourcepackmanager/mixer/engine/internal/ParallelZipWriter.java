package com.magmaguy.resourcepackmanager.mixer.engine.internal;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Writes a ZIP archive whose bytes are identical to what {@link java.util.zip.ZipOutputStream}
 * would have produced for the same ordered entry list, but compresses the entries concurrently
 * on a bounded pool instead of one at a time on the calling thread.
 *
 * <p>Deflating the merged resource pack is the single most expensive step in a mix cycle and it
 * is embarrassingly parallel: {@code ZipOutputStream} calls {@code Deflater.reset()} between
 * entries, so every entry's compressed bytes depend only on that entry's own content. This class
 * exploits that — worker threads produce {@code (crc, deflated bytes)} pairs, and the calling
 * thread splices them into the archive <b>in the caller's supplied order</b>.</p>
 *
 * <h2>Why byte-identity matters</h2>
 * <p>RSPM hands clients a SHA-1 of the archive. {@link ZipUtil} sorts directory children and pins
 * {@code setTime(0L)} on every entry precisely so an unchanged pack hashes to an unchanged value
 * across restarts. Anything that perturbs the archive bytes forces every player on the server to
 * redownload the pack. So this writer does not merely produce <i>a</i> deterministic archive — it
 * reproduces {@code ZipOutputStream}'s exact container layout:</p>
 * <ul>
 *   <li>local headers carry the data-descriptor flag ({@code 0x08}) plus the UTF-8 name flag
 *       ({@code 0x800}) and zeroed crc/size fields, because {@code ZipOutputStream} does not know
 *       an entry's size when it writes the header;</li>
 *   <li>each entry's real crc/sizes follow the compressed bytes in a data descriptor;</li>
 *   <li>the central directory and end-of-central-directory records match field for field,
 *       including the ZIP64 promotions {@code ZipOutputStream} applies past 4 GiB / 65,535 entries.</li>
 * </ul>
 * <p>{@code ParallelZipWriterDeterminismTest} pins this down by zipping the same trees through a
 * literal {@code ZipOutputStream} and through this class and asserting identical SHA-1.</p>
 *
 * <h2>Cancellation</h2>
 * <p>{@link CancellationException} propagates from both the splice loop and the worker tasks, so a
 * shutdown mid-archive aborts promptly rather than finishing a doomed 80 MB write. Workers poll the
 * supplied {@link BooleanSupplier} between deflate chunks; the pool is shut down with
 * {@code shutdownNow()} on the way out so in-flight compressions are interrupted.</p>
 */
public final class ParallelZipWriter {

    /**
     * One archive member. Every entry's time is pinned to {@code 0L} (the value the serial code
     * passed to {@code ZipEntry.setTime(...)}) so unchanged content zips to unchanged bytes.
     */
    public record Entry(String name, Path file) {
    }

    private static final int LOCSIG = 0x04034b50;
    private static final int EXTSIG = 0x08074b50;
    private static final int CENSIG = 0x02014b50;
    private static final int ENDSIG = 0x06054b50;
    private static final int ZIP64_ENDSIG = 0x06064b50;
    private static final int ZIP64_LOCSIG = 0x07064b50;
    private static final int ZIP64_EXTID = 0x0001;
    /** Info-ZIP "UT" extended timestamp extra field. */
    private static final int EXTID_EXTT = 0x5455;
    private static final int EXTT_FLAG_LMT = 0x1;
    private static final long ZIP64_MAGICVAL = 0xFFFFFFFFL;
    private static final int ZIP64_MAGICCOUNT = 0xFFFF;
    private static final long SIZEOF_ZIP64_END = 56;

    private static final int METHOD_DEFLATED = 8;
    private static final int VERSION_DEFLATED = 20;
    private static final int VERSION_ZIP64 = 45;
    /** Data descriptor (bit 3) + UTF-8 name (bit 11): exactly what ZipOutputStream sets. */
    private static final int ENTRY_FLAG = 0x08 | 0x800;
    // Both constants below are the fixed encodings of the pinned entry time 0L: it predates the
    // 1980 DOS epoch, so the DOS field is always the before-1980 sentinel and ZipOutputStream
    // always emits a 9-byte Info-ZIP "UT" extra carrying the zero Unix timestamp.
    private static final long DOSTIME_BEFORE_1980 = (1L << 21) | (1L << 16);
    private static final byte[] EPOCH_EXTENDED_TIMESTAMP = {
            (byte) (EXTID_EXTT & 0xff), (byte) ((EXTID_EXTT >>> 8) & 0xff),
            5, 0, // data size: flag byte + 4-byte modification time
            (byte) EXTT_FLAG_LMT,
            0, 0, 0, 0 // Unix seconds of entry time 0L
    };

    private static final int INPUT_CHUNK = 64 * 1024;
    private static final int DEFLATE_CHUNK = 16 * 1024;
    private static final int MEMORY_CHUNK = 64 * 1024;
    private static final int MAX_WORKERS = 4;
    private static final int MAX_PENDING_ENTRIES = 8;
    private static final long IN_FLIGHT_BYTE_BUDGET = 32L * 1024 * 1024;
    private static final long MAX_IN_MEMORY_RAW_SIZE = 8L * 1024 * 1024;
    private static final long STREAMING_WORKING_SET = INPUT_CHUNK + DEFLATE_CHUNK + MEMORY_CHUNK;

    private static final AtomicInteger POOL_INDEX = new AtomicInteger();

    private ParallelZipWriter() {
    }

    /**
     * Writes {@code entries} to {@code out} in the order given. The caller owns {@code out} and is
     * responsible for closing it; this method flushes but does not close.
     *
     * @throws CancellationException if {@code cancellationRequested} trips (or the thread is
     *                               interrupted) at any point during the write
     */
    public static void write(List<Entry> entries, OutputStream out,
                             BooleanSupplier cancellationRequested) throws IOException {
        checkCancelled(cancellationRequested);
        CountingOutputStream counting = new CountingOutputStream(out);
        List<CentralRecord> central = new ArrayList<>(entries.size());

        if (!entries.isEmpty()) {
            compressAndSplice(entries, counting, central, cancellationRequested);
        }

        checkCancelled(cancellationRequested);
        long centralOffset = counting.count();
        for (CentralRecord record : central) {
            writeCentralHeader(counting, record);
        }
        long centralLength = counting.count() - centralOffset;
        writeEnd(counting, central.size(), centralOffset, centralLength);
        counting.flush();
    }

    private static void compressAndSplice(List<Entry> entries, CountingOutputStream out,
                                          List<CentralRecord> central,
                                          BooleanSupplier cancellationRequested) throws IOException {
        try (CompressionOperation operation = new CompressionOperation(
                Math.min(MAX_WORKERS, entries.size()))) {
            ArrayDeque<PendingCompression> inFlight =
                    new ArrayDeque<>(MAX_PENDING_ENTRIES);
            int next = 0;
            long reservedBytes = 0L;

            while (next < entries.size() || !inFlight.isEmpty()) {
                checkCancelled(cancellationRequested);
                while (next < entries.size() && inFlight.size() < MAX_PENDING_ENTRIES) {
                    Entry entry = entries.get(next);
                    CompressionPlan plan = compressionPlan(entry);
                    if (reservedBytes + plan.reservedBytes() > IN_FLIGHT_BYTE_BUDGET
                            && !inFlight.isEmpty()) {
                        break;
                    }
                    Future<Compressed> future = operation.submit(
                            compressionTask(entry, plan, operation.tempDirectory(),
                                    cancellationRequested));
                    inFlight.add(new PendingCompression(future, plan.reservedBytes()));
                    reservedBytes += plan.reservedBytes();
                    next++;
                }

                PendingCompression pending = inFlight.removeFirst();
                try (Compressed compressed = await(pending.future())) {
                    long offset = out.count();
                    writeLocalHeader(out, compressed);
                    compressed.payload().writeTo(out, cancellationRequested);
                    writeDataDescriptor(out, compressed);
                    central.add(new CentralRecord(
                            compressed.nameBytes(), compressed.xdostime(),
                            compressed.extendedTimestamp(), compressed.crc(),
                            compressed.compressedSize(), compressed.rawLength(), offset));
                } finally {
                    reservedBytes -= pending.reservedBytes();
                }
            }
        }
    }

    private static Compressed await(Future<Compressed> future) throws IOException {
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Resource pack archive operation cancelled");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof CancellationException cancelled) throw cancelled;
            if (cause instanceof IOException ioException) throw ioException;
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IOException("Failed to compress resource pack entry", cause);
        }
    }

    private static Callable<Compressed> compressionTask(Entry entry,
                                                         CompressionPlan plan,
                                                         Path operationTempDirectory,
                                                         BooleanSupplier cancellationRequested) {
        return () -> {
            checkCancelled(cancellationRequested);
            CRC32 crc = new CRC32();
            // Same construction ZipOutputStream uses: DEFAULT_COMPRESSION, raw deflate stream.
            // zlib's output under Z_NO_FLUSH does not depend on how the input was chunked, so
            // streaming 64 KB chunks yields the same bytes as the prior serial loop.
            Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
            SpoolingOutput compressed = new SpoolingOutput(
                    operationTempDirectory, plan.memoryLimit(), plan.directToDisk());
            boolean payloadTransferred = false;
            try {
                long rawLength = 0L;
                byte[] inputBuffer = new byte[INPUT_CHUNK];
                byte[] outputBuffer = new byte[DEFLATE_CHUNK];
                try (InputStream input = new BufferedInputStream(Files.newInputStream(entry.file()))) {
                    int read;
                    while ((read = input.read(inputBuffer)) != -1) {
                        checkCancelled(cancellationRequested);
                        if (read == 0) continue;
                        crc.update(inputBuffer, 0, read);
                        rawLength += read;
                        deflater.setInput(inputBuffer, 0, read);
                        while (!deflater.needsInput()) {
                            checkCancelled(cancellationRequested);
                            int produced = deflater.deflate(outputBuffer, 0, outputBuffer.length);
                            if (produced > 0) compressed.write(outputBuffer, 0, produced);
                        }
                    }
                }
                deflater.finish();
                while (!deflater.finished()) {
                    checkCancelled(cancellationRequested);
                    int produced = deflater.deflate(outputBuffer, 0, outputBuffer.length);
                    if (produced > 0) compressed.write(outputBuffer, 0, produced);
                }
                CompressedPayload payload = compressed.complete();
                payloadTransferred = true;
                return new Compressed(
                        entry.name().getBytes(StandardCharsets.UTF_8),
                        DOSTIME_BEFORE_1980,
                        EPOCH_EXTENDED_TIMESTAMP,
                        crc.getValue(),
                        payload,
                        rawLength);
            } finally {
                deflater.end();
                if (!payloadTransferred) compressed.close();
            }
        };
    }

    private static CompressionPlan compressionPlan(Entry entry) throws IOException {
        long rawSize = Files.size(entry.file());
        if (rawSize < 0L || rawSize > MAX_IN_MEMORY_RAW_SIZE) {
            return new CompressionPlan(true, 0L, STREAMING_WORKING_SET);
        }
        long memoryLimit = estimatedDeflateBound(rawSize);
        long reserved = Math.min(IN_FLIGHT_BYTE_BUDGET, memoryLimit + STREAMING_WORKING_SET);
        return new CompressionPlan(false, memoryLimit, reserved);
    }

    private static long estimatedDeflateBound(long rawSize) {
        // zlib's conservative deflateBound shape. If a changing input ever exceeds
        // this estimate, SpoolingOutput switches that entry to disk before growing.
        long bound = rawSize + ((rawSize + 7L) >>> 3) + ((rawSize + 63L) >>> 6) + 64L;
        return Math.max(64L, bound);
    }

    private static ThreadFactory threadFactory() {
        int poolId = POOL_INDEX.incrementAndGet();
        AtomicInteger threadId = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable,
                    "RSPM-zip-" + poolId + "-" + threadId.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        };
    }

    // ------------------------------------------------------------------
    // Container layout — mirrors java.util.zip.ZipOutputStream field for field.
    // ------------------------------------------------------------------

    private static void writeLocalHeader(CountingOutputStream out, Compressed entry) throws IOException {
        byte[] name = entry.nameBytes();
        byte[] extra = entry.extendedTimestamp();
        byte[] header = new byte[30 + name.length + extra.length];
        int position = 0;
        position = putInt(header, position, LOCSIG);
        position = putShort(header, position, VERSION_DEFLATED);
        position = putShort(header, position, ENTRY_FLAG);
        position = putShort(header, position, METHOD_DEFLATED);
        position = putInt(header, position, (int) entry.xdostime());
        // crc / compressed size / uncompressed size all land in the trailing data descriptor.
        position = putInt(header, position, 0);
        position = putInt(header, position, 0);
        position = putInt(header, position, 0);
        position = putShort(header, position, name.length);
        position = putShort(header, position, extra.length);
        System.arraycopy(name, 0, header, position, name.length);
        position += name.length;
        System.arraycopy(extra, 0, header, position, extra.length);
        out.write(header);
    }

    private static void writeDataDescriptor(CountingOutputStream out, Compressed entry) throws IOException {
        long compressedSize = entry.compressedSize();
        long rawSize = entry.rawLength();
        boolean wide = compressedSize >= ZIP64_MAGICVAL || rawSize >= ZIP64_MAGICVAL;
        byte[] descriptor = new byte[wide ? 24 : 16];
        int position = 0;
        position = putInt(descriptor, position, EXTSIG);
        position = putInt(descriptor, position, (int) entry.crc());
        if (wide) {
            position = putLong(descriptor, position, compressedSize);
            putLong(descriptor, position, rawSize);
        } else {
            position = putInt(descriptor, position, (int) compressedSize);
            putInt(descriptor, position, (int) rawSize);
        }
        out.write(descriptor);
    }

    private static void writeCentralHeader(CountingOutputStream out, CentralRecord record) throws IOException {
        long compressedSize = record.compressedSize();
        long rawSize = record.rawSize();
        long offset = record.offset();

        int zip64ExtraLength = 0;
        boolean hasZip64 = false;
        if (compressedSize >= ZIP64_MAGICVAL) {
            compressedSize = ZIP64_MAGICVAL;
            zip64ExtraLength += 8;
            hasZip64 = true;
        }
        if (rawSize >= ZIP64_MAGICVAL) {
            rawSize = ZIP64_MAGICVAL;
            zip64ExtraLength += 8;
            hasZip64 = true;
        }
        if (offset >= ZIP64_MAGICVAL) {
            offset = ZIP64_MAGICVAL;
            zip64ExtraLength += 8;
            hasZip64 = true;
        }
        byte[] timestampExtra = record.extendedTimestamp();
        int extraLength = (hasZip64 ? zip64ExtraLength + 4 : 0) + timestampExtra.length;

        byte[] name = record.nameBytes();
        byte[] header = new byte[46 + name.length + extraLength];
        int position = 0;
        position = putInt(header, position, CENSIG);
        position = putShort(header, position, hasZip64 ? VERSION_ZIP64 : VERSION_DEFLATED);
        position = putShort(header, position, hasZip64 ? VERSION_ZIP64 : VERSION_DEFLATED);
        position = putShort(header, position, ENTRY_FLAG);
        position = putShort(header, position, METHOD_DEFLATED);
        position = putInt(header, position, (int) record.xdostime());
        position = putInt(header, position, (int) record.crc());
        position = putInt(header, position, (int) compressedSize);
        position = putInt(header, position, (int) rawSize);
        position = putShort(header, position, name.length);
        position = putShort(header, position, extraLength);
        position = putShort(header, position, 0); // file comment length
        position = putShort(header, position, 0); // starting disk number
        position = putShort(header, position, 0); // internal file attributes
        position = putInt(header, position, 0);   // external file attributes
        position = putInt(header, position, (int) offset);
        System.arraycopy(name, 0, header, position, name.length);
        position += name.length;

        // ZipOutputStream writes the ZIP64 extra before the extended timestamp; order is part of
        // the byte layout we are reproducing.
        if (hasZip64) {
            position = putShort(header, position, ZIP64_EXTID);
            position = putShort(header, position, zip64ExtraLength);
            if (rawSize == ZIP64_MAGICVAL) position = putLong(header, position, record.rawSize());
            if (compressedSize == ZIP64_MAGICVAL) position = putLong(header, position, record.compressedSize());
            if (offset == ZIP64_MAGICVAL) position = putLong(header, position, record.offset());
        }
        System.arraycopy(timestampExtra, 0, header, position, timestampExtra.length);
        out.write(header);
    }

    private static void writeEnd(CountingOutputStream out, int entryCount,
                                 long centralOffset, long centralLength) throws IOException {
        boolean hasZip64 = false;
        long endLength = centralLength;
        long endOffset = centralOffset;
        int recordedCount = entryCount;
        if (endLength >= ZIP64_MAGICVAL) {
            endLength = ZIP64_MAGICVAL;
            hasZip64 = true;
        }
        if (endOffset >= ZIP64_MAGICVAL) {
            endOffset = ZIP64_MAGICVAL;
            hasZip64 = true;
        }
        if (recordedCount >= ZIP64_MAGICCOUNT) {
            recordedCount = ZIP64_MAGICCOUNT;
            hasZip64 = true;
        }

        if (hasZip64) {
            long zip64EndOffset = out.count();
            byte[] zip64End = new byte[56];
            int position = 0;
            position = putInt(zip64End, position, ZIP64_ENDSIG);
            position = putLong(zip64End, position, SIZEOF_ZIP64_END - 12);
            position = putShort(zip64End, position, VERSION_ZIP64);
            position = putShort(zip64End, position, VERSION_ZIP64);
            position = putInt(zip64End, position, 0);
            position = putInt(zip64End, position, 0);
            position = putLong(zip64End, position, entryCount);
            position = putLong(zip64End, position, entryCount);
            position = putLong(zip64End, position, centralLength);
            putLong(zip64End, position, centralOffset);
            out.write(zip64End);

            byte[] locator = new byte[20];
            position = 0;
            position = putInt(locator, position, ZIP64_LOCSIG);
            position = putInt(locator, position, 0);
            position = putLong(locator, position, zip64EndOffset);
            putInt(locator, position, 1);
            out.write(locator);
        }

        byte[] end = new byte[22];
        int position = 0;
        position = putInt(end, position, ENDSIG);
        position = putShort(end, position, 0);
        position = putShort(end, position, 0);
        position = putShort(end, position, recordedCount);
        position = putShort(end, position, recordedCount);
        position = putInt(end, position, (int) endLength);
        position = putInt(end, position, (int) endOffset);
        putShort(end, position, 0); // archive comment length
        out.write(end);
    }

    // ------------------------------------------------------------------
    // Primitives
    // ------------------------------------------------------------------

    private static int putShort(byte[] buffer, int offset, int value) {
        buffer[offset] = (byte) (value & 0xff);
        buffer[offset + 1] = (byte) ((value >>> 8) & 0xff);
        return offset + 2;
    }

    private static int putInt(byte[] buffer, int offset, int value) {
        buffer[offset] = (byte) (value & 0xff);
        buffer[offset + 1] = (byte) ((value >>> 8) & 0xff);
        buffer[offset + 2] = (byte) ((value >>> 16) & 0xff);
        buffer[offset + 3] = (byte) ((value >>> 24) & 0xff);
        return offset + 4;
    }

    private static int putLong(byte[] buffer, int offset, long value) {
        for (int index = 0; index < 8; index++) {
            buffer[offset + index] = (byte) ((value >>> (8 * index)) & 0xff);
        }
        return offset + 8;
    }

    private static void checkCancelled(BooleanSupplier cancellationRequested) {
        Cancellation.check(cancellationRequested, "Resource pack archive operation cancelled");
    }

    private record PendingCompression(Future<Compressed> future, long reservedBytes) {
    }

    private record CompressionPlan(boolean directToDisk, long memoryLimit, long reservedBytes) {
    }

    private record Compressed(byte[] nameBytes, long xdostime, byte[] extendedTimestamp, long crc,
                              CompressedPayload payload, long rawLength) implements AutoCloseable {
        private long compressedSize() {
            return payload.size();
        }

        @Override
        public void close() throws IOException {
            payload.close();
        }
    }

    private record CentralRecord(byte[] nameBytes, long xdostime, byte[] extendedTimestamp, long crc,
                                 long compressedSize, long rawSize, long offset) {
    }

    private interface CompressedPayload extends AutoCloseable {
        long size();

        void writeTo(OutputStream output, BooleanSupplier cancellationRequested) throws IOException;

        @Override
        default void close() throws IOException {
        }
    }

    private record MemoryPayload(List<byte[]> chunks, int lastChunkLength, long size)
            implements CompressedPayload {
        @Override
        public void writeTo(OutputStream output, BooleanSupplier cancellationRequested)
                throws IOException {
            for (int index = 0; index < chunks.size(); index++) {
                checkCancelled(cancellationRequested);
                byte[] chunk = chunks.get(index);
                int length = index == chunks.size() - 1 ? lastChunkLength : chunk.length;
                output.write(chunk, 0, length);
            }
        }
    }

    private record TempFilePayload(Path file, long size) implements CompressedPayload {
        @Override
        public void writeTo(OutputStream output, BooleanSupplier cancellationRequested)
                throws IOException {
            byte[] buffer = new byte[INPUT_CHUNK];
            try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    checkCancelled(cancellationRequested);
                    if (read > 0) output.write(buffer, 0, read);
                }
            }
        }

        @Override
        public void close() throws IOException {
            Files.deleteIfExists(file);
        }
    }

    /**
     * Chunked in-memory output that switches to this operation's temp directory
     * before it can exceed the bytes reserved by the scheduling budget.
     */
    private static final class SpoolingOutput extends OutputStream {
        private final Path tempDirectory;
        private final long memoryLimit;
        private List<byte[]> chunks = new ArrayList<>();
        private byte[] currentChunk;
        private int currentChunkLength;
        private Path tempFile;
        private OutputStream tempOutput;
        private long size;
        private boolean completed;

        private SpoolingOutput(Path tempDirectory, long memoryLimit, boolean directToDisk)
                throws IOException {
            this.tempDirectory = tempDirectory;
            this.memoryLimit = memoryLimit;
            if (directToDisk) spillToDisk();
        }

        @Override
        public void write(int value) throws IOException {
            byte[] one = {(byte) value};
            write(one, 0, 1);
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            if (completed) throw new IOException("Compressed entry output is already complete");
            if (offset < 0 || length < 0 || offset > buffer.length - length) {
                throw new IndexOutOfBoundsException();
            }
            if (length == 0) return;
            if (length > Long.MAX_VALUE - size) {
                throw new IOException("Compressed entry exceeds supported size");
            }
            if (tempOutput == null && length > memoryLimit - size) spillToDisk();
            if (tempOutput != null) {
                tempOutput.write(buffer, offset, length);
            } else {
                int remaining = length;
                int sourceOffset = offset;
                while (remaining > 0) {
                    if (currentChunk == null || currentChunkLength == currentChunk.length) {
                        currentChunk = new byte[MEMORY_CHUNK];
                        chunks.add(currentChunk);
                        currentChunkLength = 0;
                    }
                    int copied = Math.min(remaining, currentChunk.length - currentChunkLength);
                    System.arraycopy(buffer, sourceOffset, currentChunk, currentChunkLength, copied);
                    currentChunkLength += copied;
                    sourceOffset += copied;
                    remaining -= copied;
                }
            }
            size += length;
        }

        private void spillToDisk() throws IOException {
            if (tempOutput != null) return;
            Path candidate = Files.createTempFile(tempDirectory, "entry-", ".deflate");
            OutputStream candidateOutput = null;
            boolean accepted = false;
            try {
                candidateOutput = new BufferedOutputStream(Files.newOutputStream(
                        candidate,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING));
                for (int index = 0; index < chunks.size(); index++) {
                    byte[] chunk = chunks.get(index);
                    int length = index == chunks.size() - 1
                            ? currentChunkLength
                            : chunk.length;
                    candidateOutput.write(chunk, 0, length);
                }
                tempFile = candidate;
                tempOutput = candidateOutput;
                chunks = List.of();
                currentChunk = null;
                currentChunkLength = 0;
                accepted = true;
            } finally {
                if (!accepted) {
                    if (candidateOutput != null) {
                        try {
                            candidateOutput.close();
                        } catch (IOException ignored) {
                        }
                    }
                    Files.deleteIfExists(candidate);
                }
            }
        }

        private CompressedPayload complete() throws IOException {
            if (completed) throw new IOException("Compressed entry output is already complete");
            if (tempOutput != null) {
                tempOutput.close();
                tempOutput = null;
                completed = true;
                return new TempFilePayload(tempFile, size);
            }
            List<byte[]> completedChunks = List.copyOf(chunks);
            completed = true;
            return new MemoryPayload(completedChunks, currentChunkLength, size);
        }

        @Override
        public void close() throws IOException {
            if (completed) return;
            IOException failure = null;
            if (tempOutput != null) {
                try {
                    tempOutput.close();
                } catch (IOException closeFailure) {
                    failure = closeFailure;
                }
                tempOutput = null;
            }
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException deleteFailure) {
                    if (failure == null) failure = deleteFailure;
                    else failure.addSuppressed(deleteFailure);
                }
            }
            completed = true;
            if (failure != null) throw failure;
        }
    }

    /** Owns both workers and spill files for exactly one archive operation. */
    private static final class CompressionOperation implements AutoCloseable {
        private final Path tempDirectory;
        private final ExecutorService executor;

        private CompressionOperation(int workerCount) throws IOException {
            tempDirectory = Files.createTempDirectory("rspm-zip-");
            try {
                executor = Executors.newFixedThreadPool(workerCount, threadFactory());
            } catch (RuntimeException failure) {
                try {
                    Files.deleteIfExists(tempDirectory);
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }

        private Path tempDirectory() {
            return tempDirectory;
        }

        private Future<Compressed> submit(Callable<Compressed> task) {
            return executor.submit(task);
        }

        @Override
        public void close() throws IOException {
            executor.shutdownNow();
            boolean restoreInterrupt = Thread.interrupted();
            boolean terminated = false;
            try {
                try {
                    terminated = executor.awaitTermination(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    restoreInterrupt = true;
                }
            } finally {
                if (restoreInterrupt) Thread.currentThread().interrupt();
            }

            IOException failure = terminated
                    ? null
                    : new IOException("ZIP compression workers did not terminate promptly");
            List<Path> cleanupPaths = List.of();
            try (var paths = Files.walk(tempDirectory)) {
                cleanupPaths = paths.sorted(Comparator.reverseOrder()).toList();
            } catch (IOException walkFailure) {
                if (failure == null) failure = walkFailure;
                else failure.addSuppressed(walkFailure);
            }
            for (Path path : cleanupPaths) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException cleanupFailure) {
                    if (failure == null) failure = cleanupFailure;
                    else failure.addSuppressed(cleanupFailure);
                }
            }
            if (failure != null) throw failure;
        }
    }

    /** Tracks the archive-relative write position so local-header offsets can be recorded. */
    private static final class CountingOutputStream extends OutputStream {
        private final OutputStream delegate;
        private long count;

        private CountingOutputStream(OutputStream delegate) {
            this.delegate = delegate;
        }

        private long count() {
            return count;
        }

        @Override
        public void write(int value) throws IOException {
            delegate.write(value);
            count++;
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            delegate.write(buffer, offset, length);
            count += length;
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }
    }
}
