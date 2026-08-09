package com.magmaguy.resourcepackmanager.bedrock;

import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.config.DefaultConfig;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

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

    private static final String OWNERSHIP_FILE_NAME = ".rspm-geyser-mapping-target";

    /**
     * Deploys the Geyser custom mappings file to the detected Geyser installation.
     * The Bedrock pack zip is intentionally NOT copied — it's served live per
     * Bedrock session via {@link GeyserPackProvider}.
     *
     * @param mappingsFile the Geyser custom mappings JSON file
     */
    public static void deployMappings(File mappingsFile) {
        reconcileMappings(mappingsFile, true);
    }

    /**
     * Converges the previously owned destination to the currently configured
     * one. Provenance is read before the copy and changed only after the old
     * exact target is gone, so a Geyser path change cannot orphan an RSPM file.
     */
    public static boolean reconcileMappings(File mappingsFile, boolean deployEnabled) {
        File previousTarget = ownedMappingsTarget();
        if (!deployEnabled || mappingsFile == null || !mappingsFile.isFile()) {
            return removeMappings();
        }

        File targetFile = mappingsTarget();
        if (targetFile == null) {
            // No local Geyser is normal on network-mode backends, but a target
            // retained from an earlier configuration still has to converge away.
            return previousTarget == null || removeMappings();
        }

        File mappingsDir = targetFile.getParentFile();
        BedrockLog.debug("Detected Geyser at: " + mappingsDir.getParentFile().getAbsolutePath());
        mappingsDir.mkdirs();
        try {
            Path target = targetFile.toPath();
            Path temporary = target.resolveSibling(
                    "." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
            try {
                Files.copy(mappingsFile.toPath(), temporary,
                        StandardCopyOption.REPLACE_EXISTING);
                publishAtomically(temporary, target);
            } finally {
                Files.deleteIfExists(temporary);
            }
            BedrockLog.debug("Geyser mappings deployed to " + mappingsDir.getAbsolutePath()
                    + " — restart Geyser to apply mapping changes (the pack itself is served live).");

            if (previousTarget != null
                    && !previousTarget.toPath().toAbsolutePath().normalize().equals(
                    targetFile.toPath().toAbsolutePath().normalize())) {
                File validatedPrevious = validateOwnedTarget(previousTarget.getAbsolutePath());
                if (validatedPrevious == null) {
                    Logger.warn("Refusing to remove invalid prior Geyser mapping target: "
                            + previousTarget.getAbsolutePath());
                    return false;
                }
                try {
                    Files.deleteIfExists(validatedPrevious.toPath());
                } catch (IOException cleanupFailure) {
                    Logger.warn("Failed to remove prior Geyser custom mapping after path change: "
                            + cleanupFailure.getMessage());
                    return false;
                }
            }
            recordOwnedMappingsTarget(targetFile);
            return true;
        } catch (IOException e) {
            Logger.warn("Failed to copy mappings to Geyser custom_mappings/ directory: " + e.getMessage());
            return false;
        }
    }

    /**
     * Returns the exact Geyser custom-mapping destination without mutating it.
     * Exposed so the conversion pipeline can include this third publication
     * target in the same rollback transaction as the local ZIP and sidecar.
     */
    public static File mappingsTarget() {
        File geyserDir = detectGeyserDir();
        if (geyserDir == null) return null;
        return new File(new File(geyserDir, "custom_mappings"),
                BedrockConversion.GEYSER_MAPPINGS_NAME);
    }

    /** Persistent provenance independent of the live auto-deploy/path setting. */
    public static File ownershipFile() {
        return new File(ResourcePackManager.plugin.getDataFolder(), OWNERSHIP_FILE_NAME);
    }

    /**
     * Returns the last committed RSPM-owned mapping target. Pre-provenance
     * installs conservatively fall back to the currently detected exact RSPM
     * filename so disabling auto-deploy also cleans upgrades from older builds.
     */
    public static File ownedMappingsTarget() {
        File provenance = ownershipFile();
        if (provenance.isFile()) {
            try {
                File target = validateOwnedTarget(Files.readString(
                        provenance.toPath(), StandardCharsets.UTF_8));
                if (target != null) return target;
                Logger.warn("Ignoring invalid RSPM Geyser mapping ownership provenance at "
                        + provenance.getAbsolutePath());
            } catch (IOException e) {
                Logger.warn("Failed to read RSPM Geyser mapping ownership provenance: "
                        + e.getMessage());
            }
        }
        File detected = mappingsTarget();
        return detected != null && detected.isFile() ? detected : null;
    }

    /** Atomically records the exact non-recursive file target RSPM owns. */
    public static void recordOwnedMappingsTarget(File targetFile) {
        File provenance = ownershipFile();
        if (targetFile == null) {
            try {
                Files.deleteIfExists(provenance.toPath());
            } catch (IOException e) {
                Logger.warn("Failed to clear RSPM Geyser mapping ownership provenance: "
                        + e.getMessage());
            }
            return;
        }
        File validated = validateOwnedTarget(targetFile.getAbsolutePath());
        if (validated == null) {
            Logger.warn("Refusing to record invalid Geyser mapping target: "
                    + targetFile.getAbsolutePath());
            return;
        }
        Path target = provenance.toPath();
        Path pending = target.resolveSibling(
                "." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(target.toAbsolutePath().getParent());
            Files.writeString(pending,
                    validated.toPath().toAbsolutePath().normalize() + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            publishAtomically(pending, target);
        } catch (IOException e) {
            Logger.warn("Failed to persist RSPM Geyser mapping ownership provenance: "
                    + e.getMessage());
        } finally {
            try {
                Files.deleteIfExists(pending);
            } catch (IOException ignored) {
            }
        }
    }

    /** Remove RSPM's deployed custom mapping after an authoritative withdrawal. */
    public static boolean removeMappings() {
        Set<File> targets = new LinkedHashSet<>();
        File owned = ownedMappingsTarget();
        File detected = mappingsTarget();
        if (owned != null) targets.add(owned);
        if (detected != null) targets.add(detected);
        boolean removed = true;
        for (File target : targets) {
            File validated = validateOwnedTarget(target.getAbsolutePath());
            if (validated == null) {
                removed = false;
                Logger.warn("Refusing to remove invalid Geyser mapping target: "
                        + target.getAbsolutePath());
                continue;
            }
            try {
                Files.deleteIfExists(validated.toPath());
            } catch (IOException e) {
                removed = false;
                Logger.warn("Failed to remove stale Geyser custom mapping: " + e.getMessage());
            }
        }
        if (removed) {
            try {
                Files.deleteIfExists(ownershipFile().toPath());
            } catch (IOException e) {
                removed = false;
                Logger.warn("Failed to clear Geyser mapping ownership provenance: "
                        + e.getMessage());
            }
        }
        return removed;
    }

    private static File validateOwnedTarget(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) return null;
        try {
            Path path = Path.of(rawPath.trim()).toAbsolutePath().normalize();
            Path name = path.getFileName();
            Path parent = path.getParent();
            if (name == null || parent == null
                    || !BedrockConversion.GEYSER_MAPPINGS_NAME.equals(name.toString())
                    || parent.getFileName() == null
                    || !"custom_mappings".equals(parent.getFileName().toString())) {
                return null;
            }
            return path.toFile();
        } catch (RuntimeException invalidPath) {
            return null;
        }
    }

    private static void publishAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicMoveFailed) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
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
        // listFiles() order is filesystem-dependent; sort so the "first match"
        // is deterministic across boots (same tie-break as the proxy-side
        // GeyserMappingsDeployer.detectGeyserPluginDir).
        Arrays.sort(children, Comparator.comparing(File::getName));
        for (File child : children) {
            if (child.isDirectory() && child.getName().startsWith("Geyser-")) {
                return child;
            }
        }
        return null;
    }
}
