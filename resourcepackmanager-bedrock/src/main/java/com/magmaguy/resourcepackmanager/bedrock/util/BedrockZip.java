package com.magmaguy.resourcepackmanager.bedrock.util;

import java.io.*;
import java.nio.file.*;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
    public static File zip(File sourceDir, File outputDir, String zipName) {
        if (sourceDir == null || !sourceDir.isDirectory()) return null;
        if (outputDir == null) return null;
        if (zipName == null || zipName.isEmpty()) return null;

        if (!outputDir.exists()) outputDir.mkdirs();

        File zipFile = new File(outputDir, zipName + ".zip");
        File tmpFile = new File(outputDir, zipName + ".zip.tmp");
        Path sourcePath = sourceDir.toPath();

        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmpFile)));
             Stream<Path> stream = Files.walk(sourcePath)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> sourcePath.relativize(path).toString().replace('\\', '/')))
                    .toList();

            for (Path file : files) {
                String entryName = sourcePath.relativize(file).toString().replace('\\', '/');
                ZipEntry entry = new ZipEntry(entryName);
                entry.setTime(0L);
                zos.putNextEntry(entry);
                Files.copy(file, zos);
                zos.closeEntry();
            }
        } catch (IOException e) {
            com.magmaguy.resourcepackmanager.bedrock.BedrockLog.warn("Failed to zip Bedrock pack: " + e.getMessage());
            try { Files.deleteIfExists(tmpFile.toPath()); } catch (IOException ignored) {}
            return null;
        }

        try {
            if (zipFile.isFile() && Files.mismatch(tmpFile.toPath(), zipFile.toPath()) == -1L) {
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
        }

        return zipFile;
    }
}
