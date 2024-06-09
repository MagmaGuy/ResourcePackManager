package com.magmaguy.resourcepackmanager;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import javax.swing.event.DocumentEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ResourcePackManager extends JavaPlugin {

    private boolean EliteMobsEnabled = false;
    private boolean FreeMinecraftModelsEnabled = false;
    private boolean ModelEngineEnabled = false;
    private String FreeMinecraftModelsRSP = "FreeMinecraftModels" + File.pathSeparator + "output" + File.pathSeparator + "resourcepack.zip";
    private String EliteMobsRSP = "EliteMobs" + File.pathSeparator + "output" + File.pathSeparator + "resourcepack.zip";
    private String ModelEngineRSP = "ModelEngine" + File.pathSeparator + "output" + File.pathSeparator + "resourcepack.zip";

    @Override
    public void onEnable() {
        Logger.info("Enabling ResourcePackManager v." + this.getDescription().getVersion());

        EliteMobsEnabled = Bukkit.getPluginManager().isPluginEnabled("EliteMobs");
        if (EliteMobsEnabled && !externalResourcePackExists(EliteMobsRSP)) {
            Logger.warn("EliteMobs is enabled but ResourcePackManager could not find its resource pack!");
            EliteMobsEnabled = false;
        }
        FreeMinecraftModelsEnabled = Bukkit.getPluginManager().isPluginEnabled("FreeMinecraftModels");
        if (FreeMinecraftModelsEnabled && !externalResourcePackExists(FreeMinecraftModelsRSP)) {
            Logger.warn("FreeMinecraftModels is enabled but ResourcePackManager could not find its resource pack!");
            FreeMinecraftModelsEnabled = false;
        }
        ModelEngineEnabled = Bukkit.getPluginManager().isPluginEnabled("ModelEngine");
        if (ModelEngineEnabled && !externalResourcePackExists(ModelEngineRSP)) {
            Logger.warn("ModelEngine is enabled but ResourcePackManager could not find its resource pack!");
            ModelEngineEnabled = false;
        }

        File mixerFolder = new File(this.getDataFolder().getPath() + File.pathSeparator + "mixer");
        if (!mixerFolder.exists()) mixerFolder.mkdir();

        File outputFolder = new File(this.getDataFolder().getPath() + File.pathSeparator + "output");
        if (!outputFolder.exists()) outputFolder.mkdir();

        if (EliteMobsEnabled) {
            moveAndExtractResourcePack(EliteMobsRSP, mixerFolder);
        }
        if (FreeMinecraftModelsEnabled) {
            moveAndExtractResourcePack(FreeMinecraftModelsRSP, mixerFolder);
        }
        if (ModelEngineEnabled) {
            moveAndExtractResourcePack(ModelEngineRSP, mixerFolder);
        }

        File newResourcePack = new File(this.getDataFolder().getPath() + File.pathSeparator + "mixer" + File.pathSeparator + "ResourcePackManager");

        if (newResourcePack.exists()) {
            if (!newResourcePack.delete()) {
                Logger.warn("Failed to delete existing resource pack");
            }
        }

        //todo: iterate through the folders and subfolders of everything in the mixer folder and put all of its files in the same folder
        try {
            for (File file : mixerFolder.listFiles()){
            consolidateFiles(file, new File(mixerFolder.getParent() + File.pathSeparator + "ResourcePackManagerRSP"));
            }
        } catch (Exception e) {
            Logger.warn("Failed to move all the files");
            e.printStackTrace();
        }
    }

    private void consolidateFiles(File sourceFolder, File targetFolder) throws IOException {
        for (File fileEntry : sourceFolder.listFiles()) {

            File targetFile = new File(targetFolder.getPath() + File.separator + fileEntry.getName());

            if (fileEntry.isDirectory()) {
                if (!targetFile.exists()) {
                    targetFile.mkdirs();
                }
                consolidateFiles(fileEntry, targetFile);
            }
            else {
                if (targetFile.exists()) {
                    Logger.warn("Duplicate file: " + targetFile.getAbsolutePath());

                    // TODO: make arrangements for duplicate files
                    continue;
                }

                Files.move(fileEntry.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private boolean externalResourcePackExists(String path) {
        return new File(this.getDataFolder().getParent() + File.pathSeparator + path).exists();
    }

    private void moveAndExtractResourcePack(String sourcePath, File destinationFolder) {
        try {
            // Moving Zip file
            File sourceFile = new File(this.getDataFolder().getParent() + File.pathSeparator + sourcePath);
            File destinationFile = new File(destinationFolder.getPath() + File.pathSeparator + sourceFile.getName());
            boolean moved = sourceFile.renameTo(destinationFile);
            if (!moved) {
                throw new IOException("Failed to move resource pack to mixer folder");
            }

            // Extracting Zip file
            byte[] buffer = new byte[1024];
            ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(destinationFile));
            ZipEntry zipEntry = zipInputStream.getNextEntry();
            while (zipEntry != null) {
                File newFile = newFile(destinationFolder, zipEntry);
                FileOutputStream fileOutputStream = new FileOutputStream(newFile);
                int len;
                while ((len = zipInputStream.read(buffer)) > 0) {
                    fileOutputStream.write(buffer, 0, len);
                }
                fileOutputStream.close();
                zipEntry = zipInputStream.getNextEntry();
            }
            zipInputStream.closeEntry();
            zipInputStream.close();

            // Delete the original Zip file
            if (!destinationFile.delete()) {
                throw new IOException("Failed to delete original zip file");
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static File newFile(File destinationDir, ZipEntry zipEntry) throws IOException {
        File destFile = new File(destinationDir, zipEntry.getName());

        String destDirPath = destinationDir.getCanonicalPath();
        String destFilePath = destFile.getCanonicalPath();

        if (!destFilePath.startsWith(destDirPath + File.separator)) {
            throw new IOException("Entry is outside of the target dir: " + zipEntry.getName());
        }

        return destFile;
    }

    @Override
    public void onDisable() {
        Logger.info("Disabling ResourcePackManager");
    }
}