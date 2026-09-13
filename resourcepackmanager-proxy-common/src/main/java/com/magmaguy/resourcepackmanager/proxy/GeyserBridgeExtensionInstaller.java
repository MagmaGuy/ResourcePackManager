package com.magmaguy.resourcepackmanager.proxy;

import com.magmaguy.resourcepackmanager.bridge.UniversalPluginJarInstaller;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Installs the exact running universal RSPM JAR as a Geyser extension on
 * proxy-hosted Geyser setups. The universal artifact contains the proxy plugin
 * and Geyser extension entrypoints, so a separately nested bridge JAR would
 * create an independently versioned, stale deployment path.
 */
public final class GeyserBridgeExtensionInstaller {
    private GeyserBridgeExtensionInstaller() {
    }

    public static void install(File geyserPluginDir, ProxyLogger logger) {
        if (geyserPluginDir == null) {
            return;
        }

        Path extensionsDirectory = geyserPluginDir.toPath().resolve("extensions");
        try {
            UniversalPluginJarInstaller.Result result = UniversalPluginJarInstaller.installRunningJar(
                    extensionsDirectory, GeyserBridgeExtensionInstaller.class);
            switch (result.state()) {
                case CURRENT -> {
                }
                case INSTALLED_DIRECTLY_RESTART_REQUIRED, STAGED_FOR_GEYSER_RESTART ->
                        logger.warn("Geyser support updated. Restart your proxy to finish the update.");
            }
        } catch (IOException exception) {
            logger.warn("Failed to install RSPM Geyser bridge extension: " + exception.getMessage(), exception);
        }
    }
}
