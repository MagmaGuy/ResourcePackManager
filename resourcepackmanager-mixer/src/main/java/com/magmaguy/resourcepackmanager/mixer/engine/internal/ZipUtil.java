package com.magmaguy.resourcepackmanager.mixer.engine.internal;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;

/**
 * Platform-neutral zip/unzip helpers used by {@link com.magmaguy.resourcepackmanager.mixer.engine.MixEngine}.
 *
 * <p>The core zip/unzip behavior originated in
 * {@code com.magmaguy.magmacore.util.ZipFile} (MagmaCore, MIT), keeping this
 * module independent of Bukkit and MagmaCore. RSPM-specific filtered-output and
 * streaming-digest variants live here so every platform uses the same archive
 * policy.</p>
 */
public final class ZipUtil {
    private ZipUtil() {
    }

    public static void unzip(File zippedFile, File destinationUnzippedFile) throws IOException {
        unzip(zippedFile, destinationUnzippedFile, () -> false);
    }

    public static void unzip(File zippedFile, File destinationUnzippedFile,
                             BooleanSupplier cancellationRequested) throws IOException {
        byte[] buffer = new byte[8192];
        // Read through the central directory rather than streaming local headers:
        // content-deduplicated archives (VanillaTweaks downloads, some pack build
        // tools) point several central-directory entries at one shared local entry,
        // and a ZipInputStream walk only ever surfaces one filename per local
        // entry — silently dropping the rest (observed: animation .mcmeta and
        // texture files missing from merged packs).
        try (java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(zippedFile)) {
            java.util.Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry zipEntry = entries.nextElement();
                checkCancelled(cancellationRequested);
                File newFile = newFile(destinationUnzippedFile, zipEntry);
                // Check if directory - isDirectory() only checks for trailing '/', but Windows zips may use '\'
                String entryName = zipEntry.getName();
                boolean isDirectory = zipEntry.isDirectory() || entryName.endsWith("\\") || entryName.endsWith("/");
                if (isDirectory) {
                    if (!newFile.isDirectory() && !newFile.mkdirs()) {
                        throw new IOException("Failed to create directory " + newFile);
                    }
                } else {
                    // Fix for Windows-created archives
                    File parent = newFile.getParentFile();
                    if (!parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("Failed to create directory " + parent);
                    }

                    // Write file content
                    try (java.io.InputStream inputStream = zipFile.getInputStream(zipEntry);
                         FileOutputStream fileOutputStream = new FileOutputStream(newFile)) {
                        int len;
                        while ((len = inputStream.read(buffer)) > 0) {
                            checkCancelled(cancellationRequested);
                            fileOutputStream.write(buffer, 0, len);
                        }
                    }
                }
                long entryTime = zipEntry.getTime();
                if (entryTime >= 0) newFile.setLastModified(entryTime);
            }
        }
    }

    public static ZipResult zipJavaResourcePackWithSha1(File directory, String targetZipPath,
                                                        BooleanSupplier cancellationRequested) {
        if (!directory.exists()) {
            return new ZipResult(false, null);
        }
        Path target = Path.of(targetZipPath);
        Path temporary = Path.of(targetZipPath + ".tmp");
        try {
            Files.deleteIfExists(temporary);
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            zipInternal(directory, temporary.toString(), true, sha1, cancellationRequested);
            checkCancelled(cancellationRequested);
            publishAtomically(temporary, target);
            return new ZipResult(true, sha1.digest());
        } catch (CancellationException cancelled) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
            throw cancelled;
        } catch (IOException | NoSuchAlgorithmException e) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
            return new ZipResult(false, null);
        }
    }

    public record ZipResult(boolean success, byte[] sha1Bytes) {
    }

    private static void zipInternal(File file, String destZipFile,
                                    boolean excludeBedrockConverterBundles,
                                    MessageDigest outputDigest,
                                    BooleanSupplier cancellationRequested) throws IOException {
        checkCancelled(cancellationRequested);
        List<ParallelZipWriter.Entry> entries =
                collectZipContents(file, excludeBedrockConverterBundles, cancellationRequested);
        checkCancelled(cancellationRequested);
        // The archive is deflated on a bounded pool and spliced back together in this exact
        // (already-sorted) order, so the bytes — and therefore the SHA-1 clients cache against —
        // are identical to the old single-threaded ZipOutputStream write. See ParallelZipWriter.
        //
        // BufferedOutputStream matters more than it looks: ZipOutputStream used to hand the
        // FileOutputStream 512-byte deflate chunks, so a 20 MB pack meant ~40k unbuffered
        // write syscalls. Buffering is pure overhead removal; it cannot change the bytes.
        try (FileOutputStream fileOutputStream = new FileOutputStream(destZipFile);
             OutputStream buffered = new BufferedOutputStream(fileOutputStream, 1 << 16)) {
            OutputStream target = outputDigest == null
                    ? buffered
                    : new DigestOutputStream(buffered, outputDigest);
            ParallelZipWriter.write(entries, target, cancellationRequested);
            target.flush();
        }
    }

    private static List<ParallelZipWriter.Entry> collectZipContents(
            File file, boolean excludeBedrockConverterBundles,
            BooleanSupplier cancellationRequested) {
        List<ParallelZipWriter.Entry> entries = new ArrayList<>();
        // Avoid having the wrapper directory show up inside the zip when the caller
        // hands us a directory — we want to zip the *contents*, not the directory itself.
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                Arrays.sort(children, Comparator.comparing(File::getName));
                for (File child : children) {
                    checkCancelled(cancellationRequested);
                    if (child.isDirectory())
                        collectDirectory(child, child.getName(), entries, excludeBedrockConverterBundles,
                                cancellationRequested);
                    else
                        collectFile(child, entries, excludeBedrockConverterBundles, cancellationRequested);
                }
            }
        } else {
            collectFile(file, entries, excludeBedrockConverterBundles, cancellationRequested);
        }
        return entries;
    }

    private static void collectDirectory(File folder, String parentFolder,
                                         List<ParallelZipWriter.Entry> entries,
                                         boolean excludeBedrockConverterBundles,
                                         BooleanSupplier cancellationRequested) {
        File[] files = folder.listFiles();
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File file : files) {
            checkCancelled(cancellationRequested);
            if (file.isDirectory()) {
                collectDirectory(file, parentFolder + "/" + file.getName(), entries,
                        excludeBedrockConverterBundles, cancellationRequested);
                continue;
            }
            String entryName = parentFolder + "/" + file.getName();
            if (excludeBedrockConverterBundles && isBedrockConverterBundle(entryName)) continue;
            entries.add(new ParallelZipWriter.Entry(entryName, file.toPath()));
        }
    }

    private static void collectFile(File file, List<ParallelZipWriter.Entry> entries,
                                    boolean excludeBedrockConverterBundles,
                                    BooleanSupplier cancellationRequested) {
        checkCancelled(cancellationRequested);
        // Skip nested zips so a stray .zip in the staging dir doesn't get wrapped into the final pack.
        if (file.getName().endsWith(".zip")) return;
        if (excludeBedrockConverterBundles && isBedrockConverterBundle(file.getName())) return;
        entries.add(new ParallelZipWriter.Entry(file.getName(), file.toPath()));
    }

    static boolean isBedrockConverterBundle(String entryName) {
        if (entryName == null) return false;
        String normalized = entryName.replace('\\', '/');
        String[] segments = normalized.split("/");
        return segments.length >= 4
                && segments[0].equals("assets")
                && !segments[1].isEmpty()
                && segments[2].equals("rspm_bedrock_pack");
    }

    /**
     * Shared atomic-publish helper: move {@code temporary} onto {@code target}
     * with {@link StandardCopyOption#ATOMIC_MOVE}, falling back to a plain
     * replace when the filesystem can't do it atomically. Also used by
     * {@code BedrockConversion} and {@code NetworkSync} so the publish
     * semantics can't drift between the pipeline's writers.
     */
    public static void publishAtomically(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicMoveFailed) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void checkCancelled(BooleanSupplier cancellationRequested) {
        Cancellation.check(cancellationRequested, "Resource pack archive operation cancelled");
    }

    private static File newFile(File destinationDir, ZipEntry zipEntry) throws IOException {
        // Normalize path separators and remove trailing slashes for proper File creation
        String entryName = zipEntry.getName().replace('\\', '/');
        if (entryName.endsWith("/")) {
            entryName = entryName.substring(0, entryName.length() - 1);
        }
        // getCanonicalPath() was called twice per entry here, and on NTFS each
        // call is a real filesystem round-trip. Measured over the 6,276-entry
        // mix that cost 7.2 s of the ~10.5 s unzip phase - the single largest
        // item in the Java mix.
        //
        // Path.normalize() is pure string manipulation and gives a STRONGER
        // guarantee: getCanonicalPath() resolves symlinks, so a pre-existing
        // link inside the destination could satisfy the old check while the
        // write still landed outside it. normalize() cannot be defeated that
        // way because it never touches the filesystem.
        Path destinationRoot = destinationDir.toPath().toAbsolutePath().normalize();
        Path resolved = destinationRoot.resolve(entryName).normalize();

        // A root entry ("/" or "") normalizes to an empty name and resolves to the destination
        // itself. That is the archive's own root, not a traversal attempt, so accept it instead
        // of aborting the whole pack - MythicHUD and other packs ship one.
        if (resolved.equals(destinationRoot)) {
            return resolved.toFile();
        }

        // Zip-slip guard: refuse entries that resolve outside the destination.
        if (!resolved.startsWith(destinationRoot)) {
            throw new IOException("Entry is outside of the target dir: " + zipEntry.getName());
        }

        return resolved.toFile();
    }
}
