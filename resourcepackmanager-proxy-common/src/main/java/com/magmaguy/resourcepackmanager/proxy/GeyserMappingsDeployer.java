package com.magmaguy.resourcepackmanager.proxy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.Arrays;
import java.util.Comparator;

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
     * @param geyserPluginDir typically the result of {@link #detectGeyserPluginDir(File, String)};
     *                        may be {@code null} when no Geyser plugin is present.
     * @param mappingsFile    the merged Geyser mappings JSON to copy.
     * @param logger          where to send the one-line "deployed to ..." message.
     */
    public static boolean deploy(File geyserPluginDir, File mappingsFile, ProxyLogger logger) {
        if (mappingsFile == null || !mappingsFile.isFile()) {
            return false;
        }
        if (geyserPluginDir == null) {
            logger.info("No Geyser plugin folder detected; merged mappings stay at "
                    + mappingsFile.getAbsolutePath() + " — copy them manually if Geyser lives elsewhere.");
            return true;
        }
        File mappingsDir = new File(geyserPluginDir, "custom_mappings");
        if (!mappingsDir.exists() && !mappingsDir.mkdirs()) {
            logger.warn("Failed to create " + mappingsDir.getAbsolutePath() + " — skipping mappings deploy.");
            return false;
        }
        try {
            Path target = new File(mappingsDir, mappingsFile.getName()).toPath();
            Path temporary = target.resolveSibling(
                    "." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
            try {
                Files.copy(mappingsFile.toPath(), temporary,
                        StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(temporary, target,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException atomicMoveFailed) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            logger.info("Geyser mappings deployed to " + mappingsDir.getAbsolutePath()
                    + " — restart the proxy to apply mapping changes (the pack itself is served live).");
            return true;
        } catch (IOException e) {
            logger.warn("Failed to copy mappings to " + mappingsDir.getAbsolutePath()
                    + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Removes a previously deployed RSPM mappings document when the current
     * network no longer has custom-item mappings. This prevents a later Geyser
     * restart from registering stale items.
     */
    public static void remove(File geyserPluginDir, String mappingsFileName, ProxyLogger logger)
            throws IOException {
        if (geyserPluginDir == null || mappingsFileName == null || mappingsFileName.isBlank()) {
            return;
        }
        Path mappingsDir = geyserPluginDir.toPath()
                .resolve("custom_mappings").toAbsolutePath().normalize();
        Path target = mappingsDir.resolve(mappingsFileName).normalize();
        if (!target.getParent().equals(mappingsDir)) {
            throw new IOException("Refusing to remove a mappings path outside custom_mappings: "
                    + target);
        }
        if (Files.deleteIfExists(target)) {
            logger.info("Removed stale Geyser mappings at " + target
                    + " because the current network has no custom-item mappings.");
        }
    }

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

    /**
     * Look for any {@code Geyser-*} subdirectory under {@code proxyPluginsDir}.
     * Geyser ships separate jars for each proxy platform (Geyser-Velocity,
     * Geyser-BungeeCord, Geyser-Waterfall uses Geyser-BungeeCord) — they all
     * create a same-named plugin folder. First match wins; in practice there's
     * only ever one Geyser plugin loaded per proxy.
     *
     * @param preferredDirectoryName exact (case-insensitive) folder name to try first;
     *                               may be {@code null} or blank to skip the preference.
     * @return the matched plugin directory, or {@code null} if none found.
     */
    public static File detectGeyserPluginDir(
            File proxyPluginsDir,
            String preferredDirectoryName) {
        if (proxyPluginsDir == null || !proxyPluginsDir.isDirectory()) return null;
        File[] children = proxyPluginsDir.listFiles();
        if (children == null) return null;
        Arrays.sort(children, Comparator.comparing(File::getName));
        if (preferredDirectoryName != null && !preferredDirectoryName.isBlank()) {
            for (File child : children) {
                if (child.isDirectory()
                        && child.getName().equalsIgnoreCase(preferredDirectoryName)) {
                    return child;
                }
            }
        }
        for (File child : children) {
            if (child.isDirectory() && child.getName().startsWith("Geyser-")) {
                return child;
            }
        }
        return null;
    }
}
