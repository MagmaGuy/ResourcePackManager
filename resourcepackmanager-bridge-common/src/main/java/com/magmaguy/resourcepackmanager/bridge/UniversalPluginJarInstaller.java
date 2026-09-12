package com.magmaguy.resourcepackmanager.bridge;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Installs the exact running universal RSPM JAR into Geyser's extensions
 * directory. There is one release artifact and one hash; no nested bridge JAR
 * or independently versioned bridge payload is produced.
 */
public final class UniversalPluginJarInstaller {
    public static final String UNIVERSAL_FILE_NAME = "ResourcePackManager.jar";
    public static final String LEGACY_BRIDGE_FILE_NAME = "ResourcePackManager-GeyserBridge.jar";
    public static final String GEYSER_UPDATE_DIRECTORY_NAME = "update";

    private UniversalPluginJarInstaller() {
    }

    public enum State {
        CURRENT,
        INSTALLED_DIRECTLY_RESTART_REQUIRED,
        STAGED_FOR_GEYSER_RESTART
    }

    public record Result(Path source,
                         Path installed,
                         Path staged,
                         String sha256,
                         State state,
                         String sourceVersion,
                         String retainedVersion,
                         boolean downgradePrevented) {
    }

    public static Result installRunningJar(Path extensionsDirectory, Class<?> codeSourceAnchor)
            throws IOException {
        return install(runningJar(codeSourceAnchor), extensionsDirectory);
    }

    public static Result install(Path sourceJar, Path extensionsDirectory) throws IOException {
        Path source = sourceJar.toAbsolutePath().normalize();
        // Testbeds may expose the shared release through a symlink. Validate the
        // resolved artifact; rejecting the link itself prevents the universal
        // Geyser extension from working in the supported shared-dist layout.
        if (!Files.isRegularFile(source)) {
            throw new IOException("running RSPM code source is not a regular JAR: " + source);
        }
        UniversalPluginJarInspector.Inspection sourceInspection =
                UniversalPluginJarInspector.inspect(source);

        Path directory = extensionsDirectory.toAbsolutePath().normalize();
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Geyser extensions path is not a directory: " + directory);
        }
        Path target = directory.resolve(UNIVERSAL_FILE_NAME).normalize();
        Path legacy = directory.resolve(LEGACY_BRIDGE_FILE_NAME).normalize();
        Path updateDirectory = directory.resolve(GEYSER_UPDATE_DIRECTORY_NAME).normalize();
        Path staged = updateDirectory.resolve(UNIVERSAL_FILE_NAME).normalize();
        if (!target.getParent().equals(directory)) {
            throw new IOException("universal extension target escaped its directory");
        }

        String sourceHash = sourceInspection.sha256();
        String sourceVersion = sourceInspection.version();
        boolean targetExists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        boolean legacyExists = Files.exists(legacy, LinkOption.NOFOLLOW_LINKS);
        if (targetExists) {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("universal extension target is not a regular file: " + target);
            }
        }
        if (legacyExists && !Files.isRegularFile(legacy, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("legacy bridge path is not a regular file: " + legacy);
        }

        ExistingArtifact newestExisting = newestValidArtifact(target, legacy, staged);
        if (newestExisting != null
                && UniversalPluginJarInspector.compareVersions(
                sourceVersion, newestExisting.inspection().version()) < 0) {
            return new Result(source, target, staged, sourceHash, State.CURRENT,
                    sourceVersion, newestExisting.inspection().version(), true);
        }

        if (legacyExists || (targetExists
                && !sourceHash.equals(UniversalPluginJarInspector.sha256(target)))) {
            UniversalPluginJarPublisher.copyVerified(source, staged, sourceHash);
            return new Result(source, target, staged, sourceHash, State.STAGED_FOR_GEYSER_RESTART,
                    sourceVersion, retainedVersion(newestExisting), false);
        }

        if (targetExists) {
            if (Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS)
                    && sourceHash.equals(UniversalPluginJarInspector.sha256(staged))) {
                Files.delete(staged);
                deleteIfEmpty(updateDirectory);
            }
            return new Result(source, target, staged, sourceHash, State.CURRENT,
                    sourceVersion, sourceVersion, false);
        }

        if (Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS)
                && sourceHash.equals(UniversalPluginJarInspector.sha256(staged))) {
            return new Result(source, target, staged, sourceHash, State.STAGED_FOR_GEYSER_RESTART,
                    sourceVersion, sourceVersion, false);
        }

        UniversalPluginJarPublisher.copyVerified(source, staged, sourceHash);
        publishStagedUniversal(staged, target);
        verifyInstalledUniversal(target, sourceHash);
        deleteIfEmpty(updateDirectory);
        return new Result(source, target, staged, sourceHash, State.INSTALLED_DIRECTLY_RESTART_REQUIRED,
                sourceVersion, sourceVersion, false);
    }

    public static Path runningJar(Class<?> anchor) throws IOException {
        if (anchor == null || anchor.getProtectionDomain() == null
                || anchor.getProtectionDomain().getCodeSource() == null
                || anchor.getProtectionDomain().getCodeSource().getLocation() == null) {
            throw new IOException("could not locate the running RSPM JAR");
        }
        try {
            return Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new IOException("invalid running RSPM code-source location", e);
        }
    }

    private static void publishStagedUniversal(Path staged, Path target) throws IOException {
        try {
            Files.move(staged, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void verifyInstalledUniversal(Path target, String expectedHash) throws IOException {
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("universal extension target is not a regular file: " + target);
        }
        UniversalPluginJarInspector.Inspection inspection =
                UniversalPluginJarInspector.inspect(target);
        if (!expectedHash.equals(inspection.sha256())) {
            throw new IOException("installed universal JAR does not match the running release");
        }
    }

    private static ExistingArtifact newestValidArtifact(Path... candidates) {
        ExistingArtifact newest = null;
        for (Path candidate : candidates) {
            if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) continue;
            try {
                UniversalPluginJarInspector.Inspection inspection =
                        UniversalPluginJarInspector.inspect(candidate);
                if (newest == null
                        || UniversalPluginJarInspector.compareVersions(
                        inspection.version(), newest.inspection().version()) > 0) {
                    newest = new ExistingArtifact(inspection);
                }
            } catch (IOException ignored) {
                // Invalid old artifacts must not block a verified repair.
            }
        }
        return newest;
    }

    private static String retainedVersion(ExistingArtifact artifact) {
        return artifact == null ? null : artifact.inspection().version();
    }

    private static void deleteIfEmpty(Path directory) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (var entries = Files.list(directory)) {
            if (entries.findAny().isEmpty()) Files.delete(directory);
        }
    }

    private record ExistingArtifact(UniversalPluginJarInspector.Inspection inspection) {
    }
}
