package com.magmaguy.resourcepackmanager.bedrock.util;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Utility for zipping a Bedrock pack directory into a distributable .zip file.
 */
public class BedrockZip {

    /**
     * Zips a directory into a .zip file.
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
        Path sourcePath = sourceDir.toPath();

        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFile)))) {
            Files.walkFileTree(sourcePath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String entryName = sourcePath.relativize(file).toString().replace('\\', '/');
                    zos.putNextEntry(new ZipEntry(entryName));
                    Files.copy(file, zos);
                    zos.closeEntry();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (!dir.equals(sourcePath)) {
                        String entryName = sourcePath.relativize(dir).toString().replace('\\', '/') + "/";
                        zos.putNextEntry(new ZipEntry(entryName));
                        zos.closeEntry();
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            com.magmaguy.magmacore.util.Logger.warn("Failed to zip Bedrock pack: " + e.getMessage());
            return null;
        }

        return zipFile;
    }
}
