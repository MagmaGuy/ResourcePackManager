package com.magmaguy.resourcepackmanager.bedrock;

import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.config.DefaultConfig;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Detects a Geyser installation and deploys the Geyser custom mappings file to
 * the appropriate Geyser directory.
 * <p>
 * The Bedrock resource pack itself is NOT copied into Geyser's {@code packs/}
 * folder. Geyser scans that folder at boot only, so a copy-based deploy could
 * only ever publish a stale pack (the one from the previous server run). The
 * pack is served per-session via {@link GeyserPackProvider}, which lets the
 * pack on disk stay live as RSPM re-mixes it.
 * <p>
 * Mappings, however, ARE boot-frozen by Geyser ({@code GeyserDefineCustomItemsEvent}
 * is a lifecycle event that fires once at startup), so this class still copies
 * {@code rspm_geyser_mappings.json} into {@code custom_mappings/} so that next
 * boot picks up the latest set. Changes to the custom-item SET (new items
 * added/removed) therefore require a server restart, but texture/model tweaks
 * to existing items take effect for the next joining Bedrock player without
 * a restart.
 */
public class GeyserDeployer {

    /**
     * Deploys the Geyser custom mappings file to the detected Geyser installation.
     * The Bedrock pack zip is intentionally NOT copied — it's served live per
     * Bedrock session via {@link GeyserPackProvider}.
     *
     * @param mappingsFile the Geyser custom mappings JSON file
     */
    public static void deployMappings(File mappingsFile) {
        File geyserDir = detectGeyserDir();
        if (geyserDir == null) {
            // No local Geyser. On a backend in network mode this is EXPECTED
            // (Geyser is on the proxy). Silent — the mappings file is already
            // written to output/, which the proxy fetches via the embedded
            // HTTP server. Operators with a real "where do I copy these"
            // question can run /rspm status to see the output path.
            return;
        }

        // Per-deploy "Detected Geyser at ..." / "Geyser mappings deployed to ..."
        // fires once at startup AND once per /reload / pack mix, so on a busy
        // server it shows up in the console several times in a row even though
        // it's not surfacing new information (the path doesn't change between
        // boots). Demoted to BedrockLog.debug, gated on `bedrockConverterDebug`
        // in config.yml so operators who actually want to verify the detection
        // worked can opt back in.
        BedrockLog.debug("Detected Geyser at: " + geyserDir.getAbsolutePath());

        File mappingsDir = new File(geyserDir, "custom_mappings");
        mappingsDir.mkdirs();
        try {
            Files.copy(mappingsFile.toPath(),
                    new File(mappingsDir, mappingsFile.getName()).toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            BedrockLog.debug("Geyser mappings deployed to " + mappingsDir.getAbsolutePath()
                    + " — restart Geyser to apply mapping changes (the pack itself is served live).");
        } catch (IOException e) {
            // Real I/O failure — keep at warn so operators see the deploy actually broke.
            Logger.warn("Failed to copy mappings to Geyser custom_mappings/ directory: " + e.getMessage());
        }
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
