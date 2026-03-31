package com.magmaguy.resourcepackmanager.bedrock;

import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.config.DefaultConfig;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Detects a Geyser installation and deploys the Bedrock resource pack
 * and custom mappings file to the appropriate Geyser directories.
 */
public class GeyserDeployer {

    /**
     * Deploys the Bedrock pack zip and Geyser mappings file to the detected
     * Geyser installation directory.
     *
     * @param bedrockZip   the zipped Bedrock resource pack
     * @param mappingsFile the Geyser custom mappings JSON file
     */
    public static void deploy(File bedrockZip, File mappingsFile) {
        File geyserDir = detectGeyserDir();
        if (geyserDir == null) {
            Logger.warn("Geyser installation not detected. Bedrock pack and mappings saved to output/ folder.");
            Logger.warn("Manually copy them to your Geyser packs/ and custom_mappings/ folders.");
            return;
        }

        Logger.info("Detected Geyser at: " + geyserDir.getAbsolutePath());

        // Deploy Bedrock pack to packs/
        File packsDir = new File(geyserDir, "packs");
        packsDir.mkdirs();
        try {
            Files.copy(bedrockZip.toPath(),
                    new File(packsDir, bedrockZip.getName()).toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            Logger.info("Bedrock pack deployed to " + packsDir.getAbsolutePath());
        } catch (IOException e) {
            Logger.warn("Failed to copy Bedrock pack to Geyser packs/ directory: " + e.getMessage());
        }

        // Deploy mappings to custom_mappings/
        File mappingsDir = new File(geyserDir, "custom_mappings");
        mappingsDir.mkdirs();
        try {
            Files.copy(mappingsFile.toPath(),
                    new File(mappingsDir, mappingsFile.getName()).toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            Logger.info("Geyser mappings deployed to " + mappingsDir.getAbsolutePath());
        } catch (IOException e) {
            Logger.warn("Failed to copy mappings to Geyser custom_mappings/ directory: " + e.getMessage());
        }

        Logger.info("Bedrock pack deployed to Geyser. Restart Geyser to apply changes.");
    }

    /**
     * Detects the Geyser installation directory using multiple strategies:
     * 1. Manual override from config
     * 2. plugins/Geyser-Spigot/
     * 3. plugins/Geyser-*&#47; (any variant)
     * 4. config/Geyser-*&#47; (Fabric/NeoForge)
     *
     * @return the Geyser directory, or null if not found
     */
    private static File detectGeyserDir() {
        // Check manual override from config
        String override = DefaultConfig.getBedrockGeyserFolder();
        if (override != null && !override.isEmpty()) {
            File dir = new File(override);
            if (dir.exists() && dir.isDirectory()) return dir;

            // Try as relative path from plugins directory
            File pluginsDir = ResourcePackManager.plugin.getDataFolder().getParentFile();
            dir = new File(pluginsDir, override);
            if (dir.exists() && dir.isDirectory()) return dir;

            Logger.warn("Configured Geyser folder '" + override + "' not found.");
        }

        // Auto-detect in plugins/ directory
        File pluginsDir = ResourcePackManager.plugin.getDataFolder().getParentFile();

        // Check Geyser-Spigot first (most common)
        File spigotDir = new File(pluginsDir, "Geyser-Spigot");
        if (spigotDir.exists() && spigotDir.isDirectory()) return spigotDir;

        // Check any Geyser-* variant in plugins/
        File found = findGeyserSubdir(pluginsDir);
        if (found != null) return found;

        // Check config/ directory for Fabric/NeoForge setups
        File serverRoot = pluginsDir.getParentFile();
        if (serverRoot != null) {
            File configDir = new File(serverRoot, "config");
            if (configDir.exists() && configDir.isDirectory()) {
                found = findGeyserSubdir(configDir);
                if (found != null) return found;
            }
        }

        return null;
    }

    /**
     * Searches for a subdirectory matching "Geyser-*" within the given parent directory.
     *
     * @param parentDir the directory to search in
     * @return the first matching Geyser directory, or null
     */
    private static File findGeyserSubdir(File parentDir) {
        File[] children = parentDir.listFiles();
        if (children == null) return null;
        for (File child : children) {
            if (child.isDirectory() && child.getName().startsWith("Geyser-")) {
                return child;
            }
        }
        return null;
    }
}
