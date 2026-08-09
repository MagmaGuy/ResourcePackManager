package com.magmaguy.resourcepackmanager.proxy;

import com.magmaguy.resourcepackmanager.bridge.UniversalPluginJarInspector;
import com.magmaguy.resourcepackmanager.bridge.UniversalPluginJarInstaller;
import com.magmaguy.resourcepackmanager.bridge.UniversalPluginJarPublisher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Properties;

/**
 * Receives a backend-offered universal JAR over NetworkSync and durably
 * stages one exact artifact for both the proxy root and Geyser extension.
 *
 * <p>Each RSPM JAR is responsible for its own version reporting. This
 * coordinator deliberately does not consult any remote release metadata:
 * pinning a proxy to whatever nightbreak.io currently publishes made
 * backward and forward compatibility between backend and proxy jars
 * unworkable. The offered artifact is still inspected, downgrade-guarded
 * against the running JAR, and copied under SHA-256 verification, so a
 * corrupt or truncated artifact is still rejected. What is no longer
 * checked is provenance: a backend RSPM trusts over NetworkSync can offer
 * any JAR that parses as a universal RSPM artifact and declares a version
 * at or above the proxy's own.
 */
public final class ProxyPluginUpdateCoordinator {
    static final String PENDING_DIRECTORY = "plugin-update";
    static final String PENDING_JAR = "ResourcePackManager.jar";
    static final String PENDING_MANIFEST = "pending.properties";

    private final Path workingDirectory;
    private final Path runningJar;
    private final Path geyserPluginDirectory;
    private final ProxyLogger logger;

    public ProxyPluginUpdateCoordinator(Path workingDirectory,
                                        Path runningJar,
                                        Path geyserPluginDirectory,
                                        ProxyLogger logger) {
        this.workingDirectory = normalize(workingDirectory, "working directory");
        this.runningJar = normalize(runningJar, "running plugin JAR");
        this.geyserPluginDirectory = geyserPluginDirectory == null
                ? null : normalize(geyserPluginDirectory, "Geyser plugin directory");
        this.logger = logger;
    }

    public static ProxyPluginUpdateCoordinator production(Path workingDirectory,
                                                          Path geyserPluginDirectory,
                                                          ProxyLogger logger,
                                                          Class<?> codeSourceAnchor)
            throws IOException {
        return new ProxyPluginUpdateCoordinator(
                workingDirectory,
                UniversalPluginJarInstaller.runningJar(codeSourceAnchor),
                geyserPluginDirectory,
                logger);
    }

    /**
     * Validates and stages a newly downloaded candidate. The running proxy JAR
     * is deliberately not touched while its classloader may hold it open.
     */
    public synchronized boolean accept(Path candidate) {
        try {
            UniversalPluginJarInspector.Inspection offered =
                    UniversalPluginJarInspector.inspect(candidate);
            UniversalPluginJarInspector.Inspection current =
                    UniversalPluginJarInspector.inspect(runningJar);

            int versionComparison = UniversalPluginJarInspector.compareVersions(
                    offered.version(), current.version());
            if (versionComparison < 0) {
                logger.warn("Ignored backend-offered RSPM " + offered.version()
                        + " because this proxy already runs newer " + current.version() + ".");
                return false;
            }
            if (versionComparison == 0 && offered.sha256().equals(current.sha256())) {
                clearMatchingPending(offered.sha256());
                return false;
            }

            Path pendingDirectory = pendingDirectory();
            Files.createDirectories(pendingDirectory);
            Path pendingJar = pendingDirectory.resolve(PENDING_JAR);
            UniversalPluginJarPublisher.copyVerified(
                    offered.jar(), pendingJar, offered.sha256());

            Properties manifest = new Properties();
            manifest.setProperty("schema", "1");
            manifest.setProperty("target", runningJar.toString());
            manifest.setProperty("geyserPluginDirectory",
                    geyserPluginDirectory == null ? "" : geyserPluginDirectory.toString());
            manifest.setProperty("expectedCurrentSha256", current.sha256());
            manifest.setProperty("newSha256", offered.sha256());
            manifest.setProperty("newVersion", offered.version());
            manifest.setProperty("newSize", Long.toString(offered.sizeBytes()));
            writeManifestAtomically(manifest);
            logger.info("Downloaded and verified ResourcePackManager " + offered.version()
                    + " for this proxy. It will replace the proxy plugin and stage the same "
                    + "SHA-256 for Geyser when the proxy stops.");
            return true;
        } catch (Exception exception) {
            logger.warn("Rejected backend-offered ResourcePackManager update: "
                    + exception.getMessage(), exception);
            return false;
        }
    }

    /**
     * Applies a pending update during orderly proxy shutdown. Linux/Docker can
     * normally replace a loaded JAR; Windows may retain a file lock, in which
     * case the durable pending files remain for the prelaunch applier.
     */
    public synchronized boolean applyPendingAtShutdown() {
        return applyPending(workingDirectory, runningJar, geyserPluginDirectory, logger);
    }

    public Path pendingJar() {
        return pendingDirectory().resolve(PENDING_JAR);
    }

    public Path pendingManifest() {
        return pendingDirectory().resolve(PENDING_MANIFEST);
    }

    public static boolean applyPending(Path workingDirectory,
                                       Path expectedRunningJar,
                                       Path expectedGeyserPluginDirectory,
                                       ProxyLogger logger) {
        Path work = normalize(workingDirectory, "working directory");
        Path rootJar = normalize(expectedRunningJar, "running plugin JAR");
        Path geyserDir = expectedGeyserPluginDirectory == null
                ? null : normalize(expectedGeyserPluginDirectory, "Geyser plugin directory");
        Path pendingDirectory = work.resolve(PENDING_DIRECTORY).normalize();
        Path pendingJar = pendingDirectory.resolve(PENDING_JAR);
        Path manifestPath = pendingDirectory.resolve(PENDING_MANIFEST);
        if (!Files.isRegularFile(pendingJar, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }

        Path backup = pendingDirectory.resolve("current-backup.jar");
        try {
            Properties manifest = readManifest(manifestPath);
            if (!"1".equals(manifest.getProperty("schema"))) {
                throw new IOException("unsupported pending-update manifest schema");
            }
            if (!rootJar.equals(Path.of(required(manifest, "target")).toAbsolutePath().normalize())) {
                throw new IOException("pending update targets a different proxy JAR");
            }
            String manifestGeyser = required(manifest, "geyserPluginDirectory");
            Path recordedGeyser = manifestGeyser.isBlank()
                    ? null : Path.of(manifestGeyser).toAbsolutePath().normalize();
            if (!java.util.Objects.equals(geyserDir, recordedGeyser)) {
                throw new IOException("pending update targets a different Geyser directory");
            }

            UniversalPluginJarInspector.Inspection pending =
                    UniversalPluginJarInspector.inspect(pendingJar);
            String newHash = required(manifest, "newSha256").toLowerCase(Locale.ROOT);
            if (!pending.sha256().equals(newHash)
                    || !pending.version().equals(required(manifest, "newVersion"))
                    || pending.sizeBytes() != Long.parseLong(required(manifest, "newSize"))) {
                throw new IOException("pending update no longer matches its manifest");
            }

            UniversalPluginJarInspector.Inspection current =
                    UniversalPluginJarInspector.inspect(rootJar);
            boolean rootAlreadyUpdated = current.sha256().equals(newHash);
            if (!rootAlreadyUpdated
                    && !current.sha256().equals(required(manifest, "expectedCurrentSha256"))) {
                throw new IOException("proxy root changed after the update was staged; refusing to overwrite it");
            }

            if (!rootAlreadyUpdated) {
                UniversalPluginJarPublisher.copyVerified(
                        rootJar, backup, current.sha256());
                publishVerified(pendingJar, rootJar, newHash);
            }

            try {
                if (geyserDir != null) {
                    UniversalPluginJarInstaller.Result installed =
                            UniversalPluginJarInstaller.install(
                                    rootJar, geyserDir.resolve("extensions"));
                    if (installed.downgradePrevented()) {
                        throw new IOException("Geyser already has newer RSPM "
                                + installed.retainedVersion() + "; refusing a split-version update");
                    }
                }
            } catch (Exception geyserFailure) {
                if (!rootAlreadyUpdated && Files.isRegularFile(backup)) {
                    publishVerified(backup, rootJar,
                            UniversalPluginJarInspector.sha256(backup));
                }
                throw geyserFailure;
            }

            Files.deleteIfExists(backup);
            Files.deleteIfExists(pendingJar);
            Files.deleteIfExists(manifestPath);
            deleteIfEmpty(pendingDirectory);
            logger.info("Applied verified ResourcePackManager " + pending.version()
                    + " to the proxy root and Geyser update path. Restart will load it.");
            return true;
        } catch (Exception exception) {
            logger.warn("Could not apply the pending proxy update; it remains at "
                    + pendingJar + ". Stop the proxy and run the RSPM prelaunch applier: "
                    + exception.getMessage(), exception);
            return false;
        }
    }

    private void clearMatchingPending(String currentHash) throws IOException {
        if (!Files.isRegularFile(pendingJar())) return;
        UniversalPluginJarInspector.Inspection pending =
                UniversalPluginJarInspector.inspect(pendingJar());
        if (!pending.sha256().equals(currentHash)) return;
        Files.deleteIfExists(pendingJar());
        Files.deleteIfExists(pendingManifest());
        deleteIfEmpty(pendingDirectory());
    }

    private Path pendingDirectory() {
        Path directory = workingDirectory.resolve(PENDING_DIRECTORY).normalize();
        if (!directory.getParent().equals(workingDirectory)) {
            throw new IllegalStateException("pending update directory escaped the working directory");
        }
        return directory;
    }

    private void writeManifestAtomically(Properties manifest) throws IOException {
        Path directory = pendingDirectory();
        Path temporary = Files.createTempFile(directory, ".pending.", ".properties.part");
        try {
            try (OutputStream output = Files.newOutputStream(
                    temporary, StandardOpenOption.TRUNCATE_EXISTING)) {
                manifest.store(output, "ResourcePackManager verified proxy update");
            }
            force(temporary);
            moveReplacing(temporary, pendingManifest());
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Properties readManifest(Path path) throws IOException {
        Properties manifest = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            manifest.load(input);
        }
        return manifest;
    }

    private static String required(Properties manifest, String key) throws IOException {
        String value = manifest.getProperty(key);
        if (value == null) throw new IOException("pending manifest is missing " + key);
        return value;
    }

    private static void publishVerified(Path source, Path target, String expectedHash)
            throws IOException {
        UniversalPluginJarPublisher.copyVerified(source, target, expectedHash);
        if (!UniversalPluginJarInspector.sha256(target).equals(expectedHash)) {
            throw new IOException("published proxy update failed SHA-256 verification");
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void force(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void deleteIfEmpty(Path directory) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (var entries = Files.list(directory)) {
            if (entries.findAny().isEmpty()) Files.delete(directory);
        }
    }

    private static Path normalize(Path path, String label) {
        if (path == null) throw new IllegalArgumentException(label + " is required");
        return path.toAbsolutePath().normalize();
    }
}
