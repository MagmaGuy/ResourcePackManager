package com.magmaguy.resourcepackmanager.update;

import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.bridge.UniversalPluginJarInspector;
import com.magmaguy.resourcepackmanager.bridge.UniversalPluginJarInstaller;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the newest validated universal RSPM artifact this backend can offer
 * to authenticated proxies. The staged Bukkit update wins before restart; the
 * running jar keeps the route useful after Bukkit consumes its update folder.
 */
public final class BackendPluginUpdateArtifactProvider {
    private static volatile Path downloadedUpdate;

    private BackendPluginUpdateArtifactProvider() {
    }

    public static boolean recordDownloaded(File file) {
        if (file == null) return false;
        try {
            UniversalPluginJarInspector.inspect(file.toPath());
            downloadedUpdate = file.toPath().toAbsolutePath().normalize();
            return true;
        } catch (Exception exception) {
            if (ResourcePackManager.plugin != null) {
                ResourcePackManager.plugin.getLogger().warning(
                        "Downloaded update is not a valid universal ResourcePackManager JAR: "
                                + exception.getMessage());
            }
            return false;
        }
    }

    public static File current(JavaPlugin plugin) {
        if (plugin == null) return null;
        File pluginsDirectory = plugin.getDataFolder().getParentFile();
        Path runningJar = null;
        try {
            runningJar = UniversalPluginJarInstaller.runningJar(ResourcePackManager.class);
        } catch (Exception ignored) {
            // Exploded test classpaths are not universal JARs.
        }
        return current(pluginsDirectory == null ? null : pluginsDirectory.toPath(), runningJar);
    }

    static File current(Path pluginsDirectory, Path runningJar) {
        List<Path> candidates = new ArrayList<>();
        if (downloadedUpdate != null) candidates.add(downloadedUpdate);
        if (pluginsDirectory != null) {
            candidates.add(pluginsDirectory
                    .resolve("update")
                    .resolve(UniversalPluginJarInstaller.UNIVERSAL_FILE_NAME));
        }
        if (runningJar != null) candidates.add(runningJar);

        UniversalPluginJarInspector.Inspection newest = null;
        for (Path candidate : candidates) {
            if (candidate == null || !Files.isRegularFile(candidate)) continue;
            try {
                UniversalPluginJarInspector.Inspection inspected =
                        UniversalPluginJarInspector.inspect(candidate);
                if (newest == null || UniversalPluginJarInspector.compareVersions(
                        inspected.version(), newest.version()) > 0) {
                    newest = inspected;
                }
            } catch (Exception exception) {
                if (candidate.equals(downloadedUpdate)) downloadedUpdate = null;
            }
        }
        return newest == null ? null : newest.jar().toFile();
    }
}
