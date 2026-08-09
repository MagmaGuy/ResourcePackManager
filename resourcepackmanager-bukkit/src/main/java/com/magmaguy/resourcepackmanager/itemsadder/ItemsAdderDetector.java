package com.magmaguy.resourcepackmanager.itemsadder;

import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.utils.RSPLogger;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

/**
 * Detects ItemsAdder installation and checks its hosting configuration.
 */
public class ItemsAdderDetector {

    private static final String COMPRESS_JSON_FILES = "resource-pack.zip.compress-json-files";

    private ItemsAdderDetector() {
    }

    /**
     * Check if ItemsAdder plugin is installed and enabled.
     * @return true if ItemsAdder is installed and enabled
     */
    public static boolean isItemsAdderInstalled() {
        return Bukkit.getPluginManager().isPluginEnabled("ItemsAdder");
    }

    /**
     * Check if ItemsAdder is currently configured to host its own resource pack.
     * This checks all built-in hosting methods: self-host, external-host, and lobfile.
     * @return true if ItemsAdder is hosting via any method
     */
    public static boolean isItemsAdderHosting() {
        if (!isItemsAdderInstalled()) return false;

        File configFile = getItemsAdderConfigFile();
        if (!configFile.exists()) {
            Logger.warn("Could not find ItemsAdder config.yml");
            return false;
        }

        return isItemsAdderHosting(YamlConfiguration.loadConfiguration(configFile));
    }

    /**
     * Same check against an already-loaded config so multi-check callers
     * ({@link #needsConfiguration()}) parse config.yml once instead of once
     * per question.
     */
    private static boolean isItemsAdderHosting(YamlConfiguration config) {
        try {
            // Check self-host
            boolean selfHostEnabled = config.getBoolean("resource-pack.hosting.self-host.enabled", false);
            if (selfHostEnabled) {
                RSPLogger.detail("ItemsAdder is configured with self-host enabled");
                return true;
            }

            // Check external-host
            String externalHostUrl = config.getString("resource-pack.hosting.external-host.url", "");
            if (externalHostUrl != null && !externalHostUrl.isEmpty() && !externalHostUrl.equals("http://example.com/resourcepack.zip")) {
                RSPLogger.detail("ItemsAdder is configured with external-host URL: " + externalHostUrl);
                return true;
            }

            // Check lobfile hosting
            boolean lobfileEnabled = config.getBoolean("resource-pack.hosting.lobfile.enabled", false);
            if (lobfileEnabled) {
                RSPLogger.detail("ItemsAdder is configured with lobfile hosting enabled");
                return true;
            }

            // Check if no-host is enabled (this means ItemsAdder is NOT hosting)
            boolean noHostEnabled = config.getBoolean("resource-pack.hosting.no-host.enabled", false);
            if (noHostEnabled) {
                RSPLogger.detail("ItemsAdder has no-host enabled - not hosting");
                return false;
            }

            // Default: if nothing is explicitly enabled, ItemsAdder is not hosting
            return false;

        } catch (Exception e) {
            Logger.warn("Failed to read ItemsAdder config: " + e.getMessage());
            return false;
        }
    }

    /**
     * Check if ItemsAdder has encryption/protection enabled.
     * @return true if any protection is enabled
     */
    public static boolean hasProtectionEnabled() {
        if (!isItemsAdderInstalled()) return false;

        File configFile = getItemsAdderConfigFile();
        if (!configFile.exists()) return false;

        return hasProtectionEnabled(YamlConfiguration.loadConfiguration(configFile));
    }

    private static boolean hasProtectionEnabled(YamlConfiguration config) {
        try {
            boolean protection1 = config.getBoolean("resource-pack.zip.protect-file-from-unzip.protection_1", false);
            boolean protection2 = config.getBoolean("resource-pack.zip.protect-file-from-unzip.protection_2", false);
            boolean protection3 = config.getBoolean("resource-pack.zip.protect-file-from-unzip.protection_3", false);

            return protection1 || protection2 || protection3;

        } catch (Exception e) {
            Logger.warn("Failed to check ItemsAdder protection settings: " + e.getMessage());
            return false;
        }
    }

    /**
     * Check if ItemsAdder is configured to emit compressed/generated JSON.
     * RSPM needs normal JSON so the mixer and Bedrock converter can parse model files.
     * @return true if JSON compression is enabled
     */
    public static boolean hasCompressedJsonEnabled() {
        if (!isItemsAdderInstalled()) return false;

        File configFile = getItemsAdderConfigFile();
        if (!configFile.exists()) return false;

        return hasCompressedJsonEnabled(YamlConfiguration.loadConfiguration(configFile));
    }

    private static boolean hasCompressedJsonEnabled(YamlConfiguration config) {
        try {
            return config.getBoolean(COMPRESS_JSON_FILES, false);
        } catch (Exception e) {
            Logger.warn("Failed to check ItemsAdder JSON compression setting: " + e.getMessage());
            return false;
        }
    }

    /**
     * Get the ItemsAdder config.yml file location. Never returns {@code null};
     * the returned file may not exist (callers check {@link File#exists()}).
     */
    public static File getItemsAdderConfigFile() {
        File pluginsFolder = ResourcePackManager.plugin.getDataFolder().getParentFile();
        return new File(pluginsFolder, "ItemsAdder" + File.separatorChar + "config.yml");
    }

    /**
     * Check if ItemsAdder needs configuration for ResourcePackManager to host.
     * Returns true if ItemsAdder is installed but not set up for external hosting.
     * Parses config.yml exactly once per call.
     * @return true if ItemsAdder needs to be configured
     */
    public static boolean needsConfiguration() {
        if (!isItemsAdderInstalled()) return false;

        File configFile = getItemsAdderConfigFile();
        if (!configFile.exists()) {
            Logger.warn("Could not find ItemsAdder config.yml");
            return false;
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);

        // If ItemsAdder is hosting via any method, don't warn
        if (isItemsAdderHosting(config)) return false;

        try {
            boolean noHostEnabled = config.getBoolean("resource-pack.hosting.no-host.enabled", false);

            // If no-host is enabled but protections/compressed JSON are still on, needs configuration
            if (noHostEnabled && (hasProtectionEnabled(config) || hasCompressedJsonEnabled(config))) {
                return true;
            }

            // If no-host is not enabled, needs configuration
            return !noHostEnabled;

        } catch (Exception e) {
            return false;
        }
    }
}
