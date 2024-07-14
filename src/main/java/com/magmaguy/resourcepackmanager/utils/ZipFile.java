package com.magmaguy.resourcepackmanager.utils;

import com.magmaguy.resourcepackmanager.Logger;

import java.io.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class ZipFile {
    private ZipFile() {
    }

    public static boolean zip(File directory, String targetZipPath) {
        if (!directory.exists()) {
            Logger.warn("Failed to zip directory " + directory.getPath() + " because it does not exist!");
            return false;
        }

        try {
            ZipUtility.zip(directory, targetZipPath);
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    public static File unzip(File zippedFile, File destinationUnzippedFile) throws IOException {
        byte[] buffer = new byte[1024];
        ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(zippedFile));
        ZipEntry zipEntry = zipInputStream.getNextEntry();
        while (zipEntry != null) {
            File newFile = newFile(destinationUnzippedFile, zipEntry);
            if (zipEntry.isDirectory()) {
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
                FileOutputStream fileOutputStream = new FileOutputStream(newFile);
                int len;
                while ((len = zipInputStream.read(buffer)) > 0) {
                    fileOutputStream.write(buffer, 0, len);
                }
                fileOutputStream.close();
            }
            newFile.setLastModified(zipEntry.getTime());
            zipEntry = zipInputStream.getNextEntry();
        }
        zipInputStream.closeEntry();
        zipInputStream.close();
        return destinationUnzippedFile;
    }

    private static File newFile(File destinationDir, ZipEntry zipEntry) throws IOException {
        File destFile = new File(destinationDir, zipEntry.getName());

        String destDirPath = destinationDir.getCanonicalPath();
        String destFilePath = destFile.getCanonicalPath();

        if (!destFilePath.startsWith(destDirPath + File.separatorChar)) {
            throw new IOException("Entry is outside of the target dir: " + zipEntry.getName());
        }

        return destFile;
    }

    public static class ZipUtility {
        /**
         * A constant for buffer size used to read/write data
         */
        private static final int BUFFER_SIZE = 4096;

        /**
         * Compresses a list of files to a destination zip file
         *
         * @param file        File to zip
         * @param destZipFile The path of the destination zip file
         * @throws FileNotFoundException
         * @throws IOException
         */
        public static void zip(File file, String destZipFile) throws FileNotFoundException, IOException {
            FileOutputStream fileOutputStream = new FileOutputStream(destZipFile);
            ZipOutputStream zos = new ZipOutputStream(fileOutputStream);
            // This slight tweak avoids making the directory zipped be in the zipped file when what we are looking for is to
            // zip the contents of the directory, outside of the directory itself
            if (file.isDirectory()) {
                for (File file1 : file.listFiles()) {
                    if (file1.isDirectory())
                        zipDirectory(file1, file1.getName(), zos);
                    else
                        zipFile(file1, zos);
                }
            } else {
                zipFile(file, zos);
            }
            zos.flush();
            zos.close();
            fileOutputStream.close();
        }

        /**
         * Adds a directory to the current zip output stream
         *
         * @param folder       the directory to be added
         * @param parentFolder the path of parent directory
         * @param zos          the current zip output stream
         * @throws FileNotFoundException
         * @throws IOException
         */
        private static void zipDirectory(File folder, String parentFolder, ZipOutputStream zos) throws FileNotFoundException, IOException {
            for (File file : folder.listFiles()) {
                if (file.isDirectory()) {
                    zipDirectory(file, parentFolder + "/" + file.getName(), zos);
                    continue;
                }
                ZipEntry zipEntry = new ZipEntry(parentFolder + "/" + file.getName());
                zippedySplit(zos, file, zipEntry);
            }
        }

        /**
         * Adds a file to the current zip output stream
         *
         * @param file the file to be added
         * @param zos  the current zip output stream
         * @throws FileNotFoundException
         * @throws IOException
         */
        private static void zipFile(File file, ZipOutputStream zos) throws FileNotFoundException, IOException {
            ZipEntry zipEntry = new ZipEntry(file.getName());
            zippedySplit(zos, file, zipEntry);
        }

        private static void zippedySplit(ZipOutputStream zos, File file, ZipEntry zipEntry) throws IOException {
            zipEntry.setTime(0L);
            zos.putNextEntry(zipEntry);
            BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file));
            byte[] bytesIn = new byte[BUFFER_SIZE];
            int read;
            while ((read = bis.read(bytesIn)) != -1) {
                zos.write(bytesIn, 0, read);
            }
            zos.closeEntry();
            bis.close();
        }
    }
}
