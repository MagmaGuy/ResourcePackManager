package com.magmaguy.resourcepackmanager.proxy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Small standalone helper that copies a merged Geyser custom-mappings JSON into
 * the proxy's Geyser plugin folder ({@code Geyser-Velocity}, {@code Geyser-BungeeCord},
 * or any {@code Geyser-*} folder we find under {@code plugins/}). Used by
 * {@link NetworkSync} after a merge and by the Velocity/Bungee entrypoints at
 * boot to pre-deploy the previous run's mappings before Geyser's
 * {@code GeyserDefineCustomItemsEvent} fires.
 *
 * <p>Geyser's custom-item registry is boot-frozen: anything we deploy after
 * Geyser has finished loading sits on disk until the next proxy restart. That's
 * fine — the pack itself is served live by {@code GeyserBinder} via
 * {@code PackCodec.path}, so the visual side (icons, geometry, attachables)
 * updates immediately. Only the item-name mapping waits for a restart.
 */
public final class GeyserMappingsDeployer {

    private GeyserMappingsDeployer() {}

    /**
     * Copy {@code mappingsFile} into {@code <geyserPluginDir>/custom_mappings/},
     * creating the directory if needed. If {@code geyserPluginDir} is null
     * (proxy admin has no Geyser plugin installed), this is a no-op; the
     * mappings stay in the proxy's work/merged/ folder for manual copy.
     *
     * @param geyserPluginDir typically the result of {@link #detectGeyserPluginDir(File)};
     *                        may be {@code null} when no Geyser plugin is present.
     * @param mappingsFile    the merged Geyser mappings JSON to copy.
     * @param logger          where to send the one-line "deployed to ..." message.
     */
    public static void deploy(File geyserPluginDir, File mappingsFile, ProxyLogger logger) {
        if (mappingsFile == null || !mappingsFile.isFile()) {
            return;
        }
        if (geyserPluginDir == null) {
            logger.info("No Geyser plugin folder detected; merged mappings stay at "
                    + mappingsFile.getAbsolutePath() + " — copy them manually if Geyser lives elsewhere.");
            return;
        }
        File mappingsDir = new File(geyserPluginDir, "custom_mappings");
        if (!mappingsDir.exists() && !mappingsDir.mkdirs()) {
            logger.warn("Failed to create " + mappingsDir.getAbsolutePath() + " — skipping mappings deploy.");
            return;
        }
        try {
            Files.copy(mappingsFile.toPath(),
                    new File(mappingsDir, mappingsFile.getName()).toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            logger.info("Geyser mappings deployed to " + mappingsDir.getAbsolutePath()
                    + " — restart the proxy to apply mapping changes (the pack itself is served live).");
        } catch (IOException e) {
            logger.warn("Failed to copy mappings to " + mappingsDir.getAbsolutePath()
                    + ": " + e.getMessage());
        }
    }

    /**
     * Look for any {@code Geyser-*} subdirectory under {@code proxyPluginsDir}.
     * Geyser ships separate jars for each proxy platform (Geyser-Velocity,
     * Geyser-BungeeCord, Geyser-Waterfall uses Geyser-BungeeCord) — they all
     * create a same-named plugin folder. First match wins; in practice there's
     * only ever one Geyser plugin loaded per proxy.
     *
     * @return the matched plugin directory, or {@code null} if none found.
     */
    /**
     * Returns {@code true} if the given Geyser mappings JSON file has zero
     * {@code items} keys (or no {@code items} block at all). Used by the proxy
     * plugins' boot-time pre-deploy: an empty mappings file would just register
     * "this network has no RSPM custom items" with Geyser and produce a useless
     * boot-time prompt, so we skip deployment in that case.
     */
    public static boolean isEmptyMappings(File mappingsFile) {
        if (mappingsFile == null || !mappingsFile.isFile()) return true;
        try (FileReader r = new FileReader(mappingsFile, StandardCharsets.UTF_8)) {
            JsonElement el = JsonParser.parseReader(r);
            if (!el.isJsonObject()) return true;
            JsonObject root = el.getAsJsonObject();
            if (!root.has("items") || !root.get("items").isJsonObject()) return true;
            return root.getAsJsonObject("items").size() == 0;
        } catch (Exception e) {
            // If we can't read it, treat as empty so we don't deploy garbage.
            return true;
        }
    }

    public static File detectGeyserPluginDir(File proxyPluginsDir) {
        if (proxyPluginsDir == null || !proxyPluginsDir.isDirectory()) return null;
        File[] children = proxyPluginsDir.listFiles();
        if (children == null) return null;
        for (File child : children) {
            if (child.isDirectory() && child.getName().startsWith("Geyser-")) {
                return child;
            }
        }
        return null;
    }
}
