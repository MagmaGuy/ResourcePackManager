package com.magmaguy.resourcepackmanager.mixer.engine.internal;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Platform-neutral zip/unzip helpers used by {@link com.magmaguy.resourcepackmanager.mixer.engine.MixEngine}.
 *
 * <p>Implementation ported verbatim from {@code com.magmaguy.magmacore.util.ZipFile}
 * (MagmaCore, MIT) so the mixer module has zero MagmaCore dependency. Behaviour
 * (including the Windows-style trailing-{@code \} directory detection and the
 * canonical-path zip-slip guard) is preserved bit-for-bit.</p>
 */
public final class ZipUtil {
    private static final int BUFFER_SIZE = 4096;

    private ZipUtil() {
    }

    public static void unzip(File zippedFile, File destinationUnzippedFile) throws IOException {
        byte[] buffer = new byte[8192];
        try (ZipInputStream zipInputStream = new ZipInputStream(new BufferedInputStream(new FileInputStream(zippedFile)))) {
            ZipEntry zipEntry = zipInputStream.getNextEntry();
            while (zipEntry != null) {
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
                    try (FileOutputStream fileOutputStream = new FileOutputStream(newFile)) {
                        int len;
                        while ((len = zipInputStream.read(buffer)) > 0) {
                            fileOutputStream.write(buffer, 0, len);
                        }
                    }
                }
                long entryTime = zipEntry.getTime();
                if (entryTime >= 0) newFile.setLastModified(entryTime);
                zipEntry = zipInputStream.getNextEntry();
            }
        }
    }

    /**
     * Zips the contents of {@code directory} into {@code targetZipPath}. If {@code directory}
     * is itself a directory, only its contents (not the wrapper directory) are zipped.
     *
     * @return {@code true} on success, {@code false} if the source does not exist or zipping fails
     */
    public static boolean zip(File directory, String targetZipPath) {
        if (!directory.exists()) {
            return false;
        }
        try {
            zipInternal(directory, targetZipPath);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void zipInternal(File file, String destZipFile) throws IOException {
        try (FileOutputStream fileOutputStream = new FileOutputStream(destZipFile);
             ZipOutputStream zos = new ZipOutputStream(fileOutputStream)) {
            // Avoid having the wrapper directory show up inside the zip when the caller
            // hands us a directory — we want to zip the *contents*, not the directory itself.
            if (file.isDirectory()) {
                File[] children = file.listFiles();
                if (children != null) {
                    for (File child : children) {
                        if (child.isDirectory())
                            zipDirectory(child, child.getName(), zos);
                        else
                            zipFile(child, zos);
                    }
                }
            } else {
                zipFile(file, zos);
            }
            zos.flush();
        }
    }

    private static void zipDirectory(File folder, String parentFolder, ZipOutputStream zos) throws IOException {
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                zipDirectory(file, parentFolder + "/" + file.getName(), zos);
                continue;
            }
            ZipEntry zipEntry = new ZipEntry(parentFolder + "/" + file.getName());
            writeEntry(zos, file, zipEntry);
        }
    }

    private static void zipFile(File file, ZipOutputStream zos) throws IOException {
        // Skip nested zips so a stray .zip in the staging dir doesn't get wrapped into the final pack.
        if (file.getName().endsWith(".zip")) return;
        ZipEntry zipEntry = new ZipEntry(file.getName());
        writeEntry(zos, file, zipEntry);
    }

    private static void writeEntry(ZipOutputStream zos, File file, ZipEntry zipEntry) throws IOException {
        zipEntry.setTime(0L);
        zos.putNextEntry(zipEntry);
        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file))) {
            byte[] bytesIn = new byte[BUFFER_SIZE];
            int read;
            while ((read = bis.read(bytesIn)) != -1) {
                zos.write(bytesIn, 0, read);
            }
        }
        zos.closeEntry();
    }

    private static File newFile(File destinationDir, ZipEntry zipEntry) throws IOException {
        // Normalize path separators and remove trailing slashes for proper File creation
        String entryName = zipEntry.getName().replace('\\', '/');
        if (entryName.endsWith("/")) {
            entryName = entryName.substring(0, entryName.length() - 1);
        }
        File destFile = new File(destinationDir, entryName);

        String destDirPath = destinationDir.getCanonicalPath();
        String destFilePath = destFile.getCanonicalPath();

        // Zip-slip guard: refuse entries that resolve outside the destination.
        if (!destFilePath.startsWith(destDirPath + File.separatorChar)) {
            throw new IOException("Entry is outside of the target dir: " + zipEntry.getName());
        }

        return destFile;
    }
}
