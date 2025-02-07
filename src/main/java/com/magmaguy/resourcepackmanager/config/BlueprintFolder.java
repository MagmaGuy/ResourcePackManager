package com.magmaguy.resourcepackmanager.config;

import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.ZipFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

public class BlueprintFolder {
    private BlueprintFolder() {
    }

    public static void initialize() {
        Logger.info("Creating blueprint folder");
        File blueprintDirectory = new File(ResourcePackManager.plugin.getDataFolder().getAbsolutePath() + File.separatorChar + "blueprint");
        if (!blueprintDirectory.exists()) blueprintDirectory.mkdir();
        Logger.info("Copying image");
        File imageFile = new File(blueprintDirectory.getAbsolutePath() + File.separatorChar + "pack.png");
        if (!imageFile.exists()) {
            try {
                InputStream inputStream = ResourcePackManager.plugin.getResource("pack.png");
                Files.copy(inputStream, imageFile.toPath());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        Logger.info("Copying mcmeta");
        File mcmetaFile = new File(blueprintDirectory.getAbsolutePath() + File.separatorChar + "pack.mcmeta");
        if (!mcmetaFile.exists()) {
            try {
                InputStream inputStream = ResourcePackManager.plugin.getResource("pack.mcmeta");
                Files.copy(inputStream, mcmetaFile.toPath());
                inputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        try {
            ZipFile.ZipUtility.zip(blueprintDirectory, blueprintDirectory.getAbsolutePath() + File.separatorChar + "blueprint.zip");
        } catch (Exception e) {
            Logger.warn("Failed to zip blueprint resource pack!");
        }
    }
}
