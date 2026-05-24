package com.magmaguy.resourcepackmanager.network;

import com.magmaguy.magmacore.util.Logger;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * On startup, when {@link NetworkMode#isActive()} is true, copies the bundled
 * ResourcePackManager-Velocity.jar and ResourcePackManager-BungeeCord.jar from
 * this plugin's resources to {@code plugins/ResourcePackManager/proxy-extension/}.
 * Admins then scp ONE of those to their proxy's plugins/ folder. Backend logs the
 * absolute paths + network-key for easy admin paste.
 *
 * Re-extracts every boot — JARs are immutable per build; if RPM is updated,
 * admins re-copy. This is the trade-off for independent extension versioning per
 * the plan (extension version evolves slowly, so re-copies are rare).
 */
public final class ProxyExtensionExtractor {

    private ProxyExtensionExtractor() {}

    public static void extractIfNetworkMode(JavaPlugin plugin) {
        if (!NetworkMode.isActive()) return;

        File outDir = new File(plugin.getDataFolder(), "proxy-extension");
        if (!outDir.exists() && !outDir.mkdirs()) {
            Logger.warn("Failed to create proxy-extension directory at " + outDir.getAbsolutePath());
            return;
        }

        File velocityOut = new File(outDir, "ResourcePackManager-Velocity.jar");
        File bungeeOut = new File(outDir, "ResourcePackManager-BungeeCord.jar");

        boolean velocityOk = extractOne(plugin, "proxy-extension/ResourcePackManager-Velocity.jar", velocityOut);
        boolean bungeeOk = extractOne(plugin, "proxy-extension/ResourcePackManager-BungeeCord.jar", bungeeOut);

        if (!velocityOk && !bungeeOk) {
            Logger.warn("Could not extract any proxy plugin jars — bundled resources missing?");
            return;
        }

        printInstructions(velocityOut, bungeeOut, velocityOk, bungeeOk);
    }

    private static boolean extractOne(JavaPlugin plugin, String resourcePath, File outFile) {
        try (InputStream in = plugin.getResource(resourcePath)) {
            if (in == null) {
                Logger.warn("Bundled resource not found: " + resourcePath);
                return false;
            }
            Files.copy(in, outFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            Logger.warn("Failed to extract " + resourcePath + " to " + outFile.getAbsolutePath() + ": " + e.getMessage());
            return false;
        }
    }

    private static void printInstructions(File velocityOut, File bungeeOut, boolean velocityOk, boolean bungeeOk) {
        Logger.info("===== PROXY EXTENSION =====");
        Logger.info("Proxy plugin jars extracted to:");
        if (velocityOk) Logger.info("  Velocity: " + velocityOut.getAbsolutePath());
        if (bungeeOk)   Logger.info("  Bungee:   " + bungeeOut.getAbsolutePath());
        Logger.info("");
        Logger.info("Setup steps:");
        Logger.info("  1. Copy the right file (Velocity or Bungee) to your proxy's plugins/ folder.");
        Logger.info("  2. Start the proxy. The plugin will write a default config.yml.");
        Logger.info("  3. Edit plugins/ResourcePackManager/config.yml on the proxy and paste this network-key:");
        Logger.info("       " + NetworkMode.getNetworkKey());
        Logger.info("  4. Restart the proxy. Bedrock + Java clients now receive the merged pack.");
        Logger.info("===========================");
    }
}
