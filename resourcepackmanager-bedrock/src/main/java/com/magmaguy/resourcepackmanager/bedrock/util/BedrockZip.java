package com.magmaguy.resourcepackmanager.bedrock.util;

import com.magmaguy.resourcepackmanager.mixer.engine.internal.Cancellation;
import com.magmaguy.resourcepackmanager.mixer.engine.internal.PackFileIndex;
import com.magmaguy.resourcepackmanager.mixer.engine.internal.ParallelZipWriter;

import java.io.*;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Utility for zipping a Bedrock pack directory into a distributable .zip file.
 */
public class BedrockZip {

    /**
     * Zips a directory into a .zip file via atomic temp-then-rename. The write goes
     * to {@code <zipName>.zip.tmp} first; only on a clean ZipOutputStream close do we
     * {@code Files.move(... ATOMIC_MOVE)} into the final path. Guarantees that any
     * reader of {@code <zipName>.zip} sees either the previous complete zip or the
     * new complete zip — never a partially-written file. Necessary because
     * {@code GeyserPackProvider} now serves this file live per Bedrock session, so a
     * half-written zip would corrupt the pack handed to a joining player.
     *
     * @param sourceDir the directory to zip
     * @param outputDir where to place the zip
     * @param zipName   the name of the zip file (without .zip extension)
     * @return the created zip File, or null on failure
     */
    public static File zip(File sourceDir, File outputDir, String zipName,
                           BooleanSupplier cancellationRequested) {
        if (sourceDir == null || !sourceDir.isDirectory()) return null;
        if (outputDir == null) return null;
        if (zipName == null || zipName.isEmpty()) return null;

        if (!outputDir.exists()) outputDir.mkdirs();

        File zipFile = new File(outputDir, zipName + ".zip");
        File tmpFile = new File(outputDir, zipName + ".zip.tmp");
        Path sourcePath = sourceDir.toPath();

        try {
            // One traversal that keeps the attributes the directory read already produced, rather
            // than Files.walk + isRegularFile re-stat'ing every one of ~10k entries. Same sorted
            // order as before, so the archive bytes are unaffected.
            List<ParallelZipWriter.Entry> entries = PackFileIndex.sortedRegularFiles(sourcePath)
                    .stream()
                    .map(file -> new ParallelZipWriter.Entry(
                            file.relativePath(), file.path()))
                    .toList();
            checkCancelled(cancellationRequested);

            // Deflating the Bedrock bundle serially was the longest single step in a mix cycle.
            // ParallelZipWriter compresses entries on a bounded pool and splices them back in this
            // exact sorted order, reproducing ZipOutputStream's bytes exactly — which the
            // filesEqual() short-circuit below and Geyser's pack cache both depend on.
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(tmpFile), 1 << 16)) {
                ParallelZipWriter.write(entries, out, cancellationRequested);
            }
            checkCancelled(cancellationRequested);
        } catch (CancellationException cancelled) {
            try { Files.deleteIfExists(tmpFile.toPath()); } catch (IOException ignored) {}
            return null;
        } catch (IOException e) {
            com.magmaguy.resourcepackmanager.bedrock.BedrockLog.warn("Failed to zip Bedrock pack: " + e.getMessage());
            try { Files.deleteIfExists(tmpFile.toPath()); } catch (IOException ignored) {}
            return null;
        }

        try {
            checkCancelled(cancellationRequested);
            if (zipFile.isFile() && filesEqual(tmpFile.toPath(), zipFile.toPath(), cancellationRequested)) {
                Files.deleteIfExists(tmpFile.toPath());
                return zipFile;
            }
            Files.move(tmpFile.toPath(), zipFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Fall back to non-atomic on filesystems that reject ATOMIC_MOVE; better to
            // ship a non-atomic write than to fail the whole pipeline.
            try {
                Files.move(tmpFile.toPath(), zipFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e2) {
                com.magmaguy.resourcepackmanager.bedrock.BedrockLog.warn("Failed to publish Bedrock pack: " + e2.getMessage());
                try { Files.deleteIfExists(tmpFile.toPath()); } catch (IOException ignored) {}
                return null;
            }
        } catch (CancellationException cancelled) {
            try { Files.deleteIfExists(tmpFile.toPath()); } catch (IOException ignored) {}
            return null;
        }

        return zipFile;
    }

    private static boolean filesEqual(Path first, Path second,
                                      BooleanSupplier cancellationRequested) throws IOException {
        return Cancellation.contentEquals(first, second, cancellationRequested,
                "Bedrock pack archive operation cancelled");
    }

    private static void checkCancelled(BooleanSupplier cancellationRequested) {
        Cancellation.check(cancellationRequested, "Bedrock pack archive operation cancelled");
    }
}
