package com.magmaguy.resourcepackmanager.bedrock;

import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.mixer.engine.internal.Sha1;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Persistent authority for the backend Bedrock artifact set.
 *
 * <p>The ZIP and optional mappings file are not publishable merely because a
 * path happens to exist.  Only a completed conversion writes this marker, and
 * every consumer validates the marker's hashes before serving or relaying the
 * files.  Consequently an undeletable stale ZIP remains inert after an
 * authoritative withdrawal.</p>
 */
public final class BedrockOutputPublication {

    public static final String MARKER_NAME = ".rspm_bedrock_publication.properties";
    private static final String VERSION = "1";

    private static volatile Cache cache;
    private static volatile boolean revokedInThisJvm;

    private BedrockOutputPublication() {
    }

    public record Snapshot(File pack, String packSha1, File mappings, String mappingsSha1) {
        public boolean hasMappings() {
            return mappings != null;
        }
    }

    /**
     * Authorises the current local artifact set after a successful conversion.
     * The marker itself is the final atomic commit point.
     */
    public static synchronized boolean publishCurrent(File outputDir,
                                                      BooleanSupplier cancellationRequested) {
        BooleanSupplier cancelled = cancellationRequested == null ? () -> false : cancellationRequested;
        File pack = new File(outputDir, BedrockConversion.BEDROCK_PACK_NAME + ".zip");
        File mappings = new File(outputDir, BedrockConversion.GEYSER_MAPPINGS_NAME);
        if (!pack.isFile() || cancelled.getAsBoolean()) return false;

        Path marker = new File(outputDir, MARKER_NAME).toPath();
        Path pending = marker.resolveSibling("." + MARKER_NAME + "." + UUID.randomUUID() + ".tmp");
        try {
            String packSha1 = stableSha1(pack, cancelled);
            if (packSha1 == null) return false;
            boolean hasMappings = mappings.isFile();
            String mappingsSha1 = hasMappings ? stableSha1(mappings, cancelled) : null;
            if (hasMappings && mappingsSha1 == null) return false;
            if (cancelled.getAsBoolean()) return false;

            Files.createDirectories(outputDir.toPath());
            List<String> lines = hasMappings
                    ? List.of(
                    "version=" + VERSION,
                    "pack.sha1=" + packSha1,
                    "mappings.present=true",
                    "mappings.sha1=" + mappingsSha1)
                    : List.of(
                    "version=" + VERSION,
                    "pack.sha1=" + packSha1,
                    "mappings.present=false");
            Files.write(pending, lines, StandardCharsets.UTF_8);
            if (cancelled.getAsBoolean()) return false;
            moveAtomically(pending, marker);
            // The atomic marker replacement is the authority commit point. The
            // artifact transaction must never roll files back after this point;
            // stableSha1 already verified that both paths stayed unchanged while
            // their committed hashes were calculated.
            revokedInThisJvm = false;
            cache = null;
            return true;
        } catch (IOException e) {
            Logger.warn("Failed to publish Bedrock output authority marker: " + e.getMessage());
            return false;
        } finally {
            try {
                Files.deleteIfExists(pending);
            } catch (IOException ignored) {
            }
        }
    }

    /** Returns the currently authorised and hash-verified artifact set. */
    public static synchronized Snapshot current(File outputDir) {
        if (revokedInThisJvm || outputDir == null) return null;
        Path marker = new File(outputDir, MARKER_NAME).toPath();
        File pack = new File(outputDir, BedrockConversion.BEDROCK_PACK_NAME + ".zip");
        File mappings = new File(outputDir, BedrockConversion.GEYSER_MAPPINGS_NAME);
        if (!Files.isRegularFile(marker) || !pack.isFile()) return null;

        try {
            long markerStamp = Files.getLastModifiedTime(marker).toMillis();
            long markerSize = Files.size(marker);
            long packStamp = pack.lastModified();
            long packSize = pack.length();
            long mappingsStamp = mappings.isFile() ? mappings.lastModified() : -1L;
            long mappingsSize = mappings.isFile() ? mappings.length() : -1L;
            Cache prior = cache;
            if (prior != null && prior.matches(outputDir, markerStamp, markerSize,
                    packStamp, packSize, mappingsStamp, mappingsSize)) {
                return prior.snapshot();
            }

            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(marker)) {
                properties.load(input);
            }
            if (!VERSION.equals(properties.getProperty("version"))) return null;
            if (Boolean.parseBoolean(properties.getProperty("withdrawn", "false"))) return null;
            String expectedPackSha1 = normalSha1(properties.getProperty("pack.sha1"));
            if (expectedPackSha1 == null) return null;
            boolean mappingsExpected = Boolean.parseBoolean(
                    properties.getProperty("mappings.present", "false"));
            String expectedMappingsSha1 = mappingsExpected
                    ? normalSha1(properties.getProperty("mappings.sha1"))
                    : null;
            if (mappingsExpected && expectedMappingsSha1 == null) return null;
            if (mappingsExpected != mappings.isFile()) return null;

            String actualPackSha1 = Sha1.hex(pack).toLowerCase(Locale.ROOT);
            if (!expectedPackSha1.equals(actualPackSha1)) return null;
            String actualMappingsSha1 = null;
            if (mappingsExpected) {
                actualMappingsSha1 = Sha1.hex(mappings).toLowerCase(Locale.ROOT);
                if (!expectedMappingsSha1.equals(actualMappingsSha1)) return null;
            }

            Snapshot snapshot = new Snapshot(
                    pack,
                    actualPackSha1,
                    mappingsExpected ? mappings : null,
                    actualMappingsSha1);
            cache = new Cache(outputDir.getAbsolutePath(), markerStamp, markerSize,
                    packStamp, packSize, mappingsStamp, mappingsSize, snapshot);
            return snapshot;
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    /** Clears metadata cache immediately before stable publication paths mutate. */
    public static synchronized void invalidateCache() {
        cache = null;
    }

    /**
     * Revokes publication authority without deleting artifact paths. This is
     * the first phase of authoritative withdrawal so cleanup failures cannot
     * leave a hash-valid old marker publishable in this JVM.
     */
    public static synchronized boolean revokeAuthority(File outputDir) {
        revokedInThisJvm = true;
        cache = null;
        boolean removed = true;
        if (outputDir != null) {
            Path marker = new File(outputDir, MARKER_NAME).toPath();
            Path tombstone = marker.resolveSibling(
                    "." + MARKER_NAME + "." + UUID.randomUUID() + ".tmp");
            try {
                Files.createDirectories(outputDir.toPath());
                Files.write(tombstone,
                        List.of("version=" + VERSION, "withdrawn=true"),
                        StandardCharsets.UTF_8);
                moveAtomically(tombstone, marker);
            } catch (IOException tombstoneFailure) {
                removed = false;
                Logger.warn("Failed to persist Bedrock publication tombstone: "
                        + tombstoneFailure.getMessage());
                try {
                    Files.deleteIfExists(marker);
                } catch (IOException ignored) {
                }
            } finally {
                try {
                    Files.deleteIfExists(tombstone);
                } catch (IOException ignored) {
                }
            }
        }
        return removed;
    }

    /**
     * Revokes publication first, then best-effort removes every local/deployed
     * artifact. A locked leftover therefore cannot be served in this JVM.
     */
    public static synchronized boolean withdraw(File outputDir) {
        boolean removed = revokeAuthority(outputDir);
        if (outputDir != null) {
            for (String name : List.of(
                    BedrockConversion.BEDROCK_PACK_NAME + ".zip",
                    BedrockConversion.GEYSER_MAPPINGS_NAME)) {
                try {
                    Files.deleteIfExists(new File(outputDir, name).toPath());
                } catch (IOException e) {
                    removed = false;
                    Logger.warn("Failed to remove stale Bedrock publication file " + name
                            + ": " + e.getMessage());
                }
            }
        }
        GeyserDeployer.removeMappings();
        return removed;
    }

    private static String stableSha1(File file, BooleanSupplier cancelled) throws IOException {
        long size = file.length();
        long modified = file.lastModified();
        String hash = Sha1.hex(file).toLowerCase(Locale.ROOT);
        if (cancelled.getAsBoolean()) return null;
        if (!file.isFile() || size != file.length() || modified != file.lastModified()) {
            throw new IOException("Bedrock artifact changed while it was being hashed: " + file.getName());
        }
        return hash;
    }

    private static String normalSha1(String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.matches("[0-9a-f]{40}") ? normalized : null;
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicMoveFailed) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record Cache(String outputPath,
                         long markerStamp,
                         long markerSize,
                         long packStamp,
                         long packSize,
                         long mappingsStamp,
                         long mappingsSize,
                         Snapshot snapshot) {
        private boolean matches(File outputDir,
                                long currentMarkerStamp,
                                long currentMarkerSize,
                                long currentPackStamp,
                                long currentPackSize,
                                long currentMappingsStamp,
                                long currentMappingsSize) {
            return outputPath.equals(outputDir.getAbsolutePath())
                    && markerStamp == currentMarkerStamp
                    && markerSize == currentMarkerSize
                    && packStamp == currentPackStamp
                    && packSize == currentPackSize
                    && mappingsStamp == currentMappingsStamp
                    && mappingsSize == currentMappingsSize;
        }
    }
}
