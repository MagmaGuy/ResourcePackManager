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
    private static final java.util.Map<Path, CachedInspection> inspections = new java.util.HashMap<>();
    private record CachedInspection(long size, java.nio.file.attribute.FileTime modified, Object fileKey,
                                    long verifiedAt, UniversalPluginJarInspector.Inspection inspection) { }

    private BackendPluginUpdateArtifactProvider() {
    }

    public static synchronized boolean recordDownloaded(File file) {
        if (file == null) return false;
        try {
            UniversalPluginJarInspector.inspect(file.toPath());
            downloadedUpdate = file.toPath().toAbsolutePath().normalize();
            inspections.remove(downloadedUpdate);
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

    static synchronized File current(Path pluginsDirectory, Path runningJar) {
        java.util.Set<Path> candidates = new java.util.LinkedHashSet<>();
        if (downloadedUpdate != null) candidates.add(downloadedUpdate);
        if (pluginsDirectory != null) {
            candidates.add(pluginsDirectory
                    .resolve("update")
                    .resolve(UniversalPluginJarInstaller.UNIVERSAL_FILE_NAME));
        }
        if (runningJar != null) candidates.add(runningJar);

        UniversalPluginJarInspector.Inspection newest = null;
        java.util.Set<Path> visited = new java.util.HashSet<>();
        for (Path candidate : candidates) {
            if (candidate == null) continue;
            candidate = candidate.toAbsolutePath().normalize();
            if (!visited.add(candidate)) continue;
            try {
                var attributes = Files.readAttributes(candidate, java.nio.file.attribute.BasicFileAttributes.class);
                if (!attributes.isRegularFile()) continue;
                CachedInspection cached = inspections.get(candidate);
                long now = System.nanoTime();
                UniversalPluginJarInspector.Inspection inspected =
                        cached != null && cached.size() == attributes.size()
                                && cached.modified().equals(attributes.lastModifiedTime())
                                && java.util.Objects.equals(cached.fileKey(), attributes.fileKey())
                                && now - cached.verifiedAt() < java.util.concurrent.TimeUnit.MINUTES.toNanos(1)
                                ? cached.inspection() : UniversalPluginJarInspector.inspect(candidate);
                if (cached == null || cached.inspection() != inspected) {
                    var after = Files.readAttributes(candidate, java.nio.file.attribute.BasicFileAttributes.class);
                    if (after.size() != attributes.size() || !after.lastModifiedTime().equals(attributes.lastModifiedTime())
                            || !java.util.Objects.equals(after.fileKey(), attributes.fileKey())) continue;
                    inspections.put(candidate, new CachedInspection(attributes.size(), attributes.lastModifiedTime(),
                            attributes.fileKey(), now, inspected));
                }
                if (newest == null || UniversalPluginJarInspector.compareVersions(
                        inspected.version(), newest.version()) > 0) {
                    newest = inspected;
                }
            } catch (Exception exception) {
                inspections.remove(candidate);
                if (candidate.equals(downloadedUpdate)) downloadedUpdate = null;
            }
        }
        inspections.keySet().retainAll(candidates.stream().map(path -> path.toAbsolutePath().normalize()).toList());
        return newest == null ? null : newest.jar().toFile();
    }
}
