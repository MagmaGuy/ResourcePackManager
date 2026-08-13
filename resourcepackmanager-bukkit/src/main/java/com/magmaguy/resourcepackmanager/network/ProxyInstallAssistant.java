package com.magmaguy.resourcepackmanager.network;

import com.magmaguy.magmacore.util.Logger;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Stages a copy of the running universal jar for the proxy when this backend
 * is proxied but has never been provisioned with a network key.
 *
 * <p>This restores the 2.0.0-era proxy-extension assist in universal-jar form:
 * back then the backend bundled separate Velocity/Bungee jars and extracted
 * them with copy instructions; 2.2.1 removed that machinery when the single
 * universal jar made the per-platform variants obsolete, and the assisted
 * install UX silently vanished with it. The jar being universal now makes the
 * assist strictly simpler — there is exactly one file, and it is byte-identical
 * to the plugin already running here, so proxy and backend can never skew
 * versions.</p>
 *
 * <p>The staged copy is written to
 * {@code plugins/ResourcePackManager/proxy-extension/ResourcePackManager.jar}.
 * Deployment onto the proxy stays a human action: cross-machine there is no
 * credentialed channel that could place a jar remotely, and same-machine
 * guessing at another server's directory is not safe. The handshake after
 * deployment is fully automatic — the proxy mints the network key and pushes
 * signed grants on player connect.</p>
 */
public final class ProxyInstallAssistant {

    private ProxyInstallAssistant() {
    }

    /**
     * Copies the running jar into the staging folder, replacing any previous
     * copy so the staged artifact always matches the running build. Returns
     * the staged file, or {@code null} if staging failed (already logged).
     */
    public static File stageProxyJar(JavaPlugin plugin, File runningJar) {
        if (runningJar == null || !runningJar.isFile()) {
            Logger.warn("Could not stage the proxy jar: the running plugin jar was not found.");
            return null;
        }
        File outDir = new File(plugin.getDataFolder(), "proxy-extension");
        if (!outDir.isDirectory() && !outDir.mkdirs()) {
            Logger.warn("Could not stage the proxy jar: failed to create " + outDir.getAbsolutePath());
            return null;
        }
        File staged = new File(outDir, "ResourcePackManager.jar");
        try {
            Path tmp = staged.toPath().resolveSibling(staged.getName() + ".tmp");
            Files.copy(runningJar.toPath(), tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, staged.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            Logger.warn("Could not stage the proxy jar at " + staged.getAbsolutePath()
                    + ": " + exception.getMessage());
            return null;
        }
        return staged;
    }
}
