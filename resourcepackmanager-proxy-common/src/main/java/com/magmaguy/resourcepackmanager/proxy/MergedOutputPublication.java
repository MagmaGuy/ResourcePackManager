package com.magmaguy.resourcepackmanager.proxy;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

/** Persistent authority for the proxy's merged ZIP plus optional mappings. */
public final class MergedOutputPublication {

    public static final String PACK_NAME = "Bedrock.zip";
    public static final String MAPPINGS_NAME = "rspm_geyser_mappings.json";
    public static final String MARKER_NAME = ".rspm_merged_publication.properties";
    public static final String REVOCATION_NAME = ".rspm_merged_publication.revoked";
    private static final String VERSION = "1";

    private MergedOutputPublication() {
    }

    public record Snapshot(File pack, String packSha1,
                           File mappings, String mappingsSha1) {
        public boolean hasMappings() {
            return mappings != null;
        }
    }

    public static Path markerPath(File mergedDir) {
        return new File(mergedDir, MARKER_NAME).toPath();
    }

    public static Path revocationPath(File mergedDir) {
        return new File(mergedDir, REVOCATION_NAME).toPath();
    }

    /** Installs a persistent fail-closed guard before stable paths mutate. */
    public static void beginMutation(File mergedDir) throws IOException {
        Files.createDirectories(mergedDir.toPath());
        writeAtomically(
                revocationPath(mergedDir),
                List.of("version=" + VERSION, "revoked=true"));
        Files.deleteIfExists(markerPath(mergedDir));
    }

    /**
     * Hashes the exact stable files and atomically writes their commit marker
     * last. A crash before the move leaves either the revocation guard or no
     * marker, both of which fail closed on the next process start.
     */
    public static Snapshot commit(File mergedDir) throws IOException {
        File pack = new File(mergedDir, PACK_NAME);
        File mappings = new File(mergedDir, MAPPINGS_NAME);
        if (!pack.isFile()) throw new IOException("Merged Bedrock ZIP is missing");
        String packSha1 = sha1(pack);
        boolean mappingsPresent = mappings.isFile();
        String mappingsSha1 = mappingsPresent ? sha1(mappings) : null;

        Path marker = markerPath(mergedDir);
        Path pending = marker.resolveSibling(
                "." + marker.getFileName() + "." + UUID.randomUUID() + ".tmp");
        List<String> lines = mappingsPresent
                ? List.of(
                "version=" + VERSION,
                "pack.sha1=" + packSha1,
                "pack.size=" + pack.length(),
                "mappings.present=true",
                "mappings.sha1=" + mappingsSha1,
                "mappings.size=" + mappings.length())
                : List.of(
                "version=" + VERSION,
                "pack.sha1=" + packSha1,
                "pack.size=" + pack.length(),
                "mappings.present=false");
        try {
            Files.write(pending, lines, StandardCharsets.UTF_8);
            // beginMutation removed the old marker. With no marker present,
            // dropping the guard still leaves the set unpublishable; the marker
            // move below is therefore the sole authority commit point.
            Files.deleteIfExists(revocationPath(mergedDir));
            moveAtomically(pending, marker);
            return new Snapshot(
                    pack, packSha1,
                    mappingsPresent ? mappings : null,
                    mappingsSha1);
        } catch (IOException failure) {
            try {
                writeAtomically(
                        revocationPath(mergedDir),
                        List.of("version=" + VERSION, "revoked=true"));
            } catch (IOException guardFailure) {
                failure.addSuppressed(guardFailure);
            }
            throw failure;
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    /** Persists withdrawal before best-effort deletion of stable paths. */
    public static boolean revoke(File mergedDir) {
        if (mergedDir == null) return false;
        boolean persisted = true;
        try {
            Files.createDirectories(mergedDir.toPath());
            writeAtomically(
                    revocationPath(mergedDir),
                    List.of("version=" + VERSION, "revoked=true"));
        } catch (IOException failure) {
            persisted = false;
        }
        Path marker = markerPath(mergedDir);
        Path pending = marker.resolveSibling(
                "." + marker.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.write(pending,
                    List.of("version=" + VERSION, "withdrawn=true"),
                    StandardCharsets.UTF_8);
            moveAtomically(pending, marker);
        } catch (IOException failure) {
            persisted = false;
            try {
                Files.deleteIfExists(marker);
            } catch (IOException ignored) {
            }
        } finally {
            try {
                Files.deleteIfExists(pending);
            } catch (IOException ignored) {
            }
        }
        return persisted;
    }

    /** Returns only a marker-authorized, byte-for-byte verified merged set. */
    public static Snapshot current(File mergedDir) {
        if (mergedDir == null || Files.exists(revocationPath(mergedDir))) return null;
        Path marker = markerPath(mergedDir);
        File pack = new File(mergedDir, PACK_NAME);
        File mappings = new File(mergedDir, MAPPINGS_NAME);
        if (!Files.isRegularFile(marker) || !pack.isFile()) return null;
        try {
            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(marker)) {
                properties.load(input);
            }
            if (!VERSION.equals(properties.getProperty("version"))
                    || Boolean.parseBoolean(properties.getProperty("withdrawn", "false"))) {
                return null;
            }
            String expectedPackSha1 = normalizedSha1(
                    properties.getProperty("pack.sha1"));
            long expectedPackSize = parseSize(properties.getProperty("pack.size"));
            boolean mappingsExpected = Boolean.parseBoolean(
                    properties.getProperty("mappings.present", "false"));
            String expectedMappingsSha1 = mappingsExpected
                    ? normalizedSha1(properties.getProperty("mappings.sha1"))
                    : null;
            long expectedMappingsSize = mappingsExpected
                    ? parseSize(properties.getProperty("mappings.size"))
                    : -1L;
            if (expectedPackSha1 == null || expectedPackSize < 0L
                    || pack.length() != expectedPackSize
                    || mappingsExpected != mappings.isFile()
                    || (mappingsExpected && (expectedMappingsSha1 == null
                    || expectedMappingsSize < 0L
                    || mappings.length() != expectedMappingsSize))) {
                return null;
            }
            String actualPackSha1 = sha1(pack);
            if (!expectedPackSha1.equals(actualPackSha1)) return null;
            String actualMappingsSha1 = null;
            if (mappingsExpected) {
                actualMappingsSha1 = sha1(mappings);
                if (!expectedMappingsSha1.equals(actualMappingsSha1)) return null;
            }
            return new Snapshot(
                    pack, actualPackSha1,
                    mappingsExpected ? mappings : null,
                    actualMappingsSha1);
        } catch (IOException | RuntimeException invalid) {
            return null;
        }
    }

    private static long parseSize(String value) {
        if (value == null) return -1L;
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed < 0L ? -1L : parsed;
        } catch (NumberFormatException invalid) {
            return -1L;
        }
    }

    private static String normalizedSha1(String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.matches("[0-9a-f]{40}") ? normalized : null;
    }

    private static String sha1(File file) throws IOException {
        try (var input = Files.newInputStream(file.toPath())) {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) > 0) digest.update(buffer, 0, read);
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-1 algorithm unavailable", impossible);
        }
    }

    private static void writeAtomically(Path target, List<String> lines) throws IOException {
        Path pending = target.resolveSibling(
                "." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.write(pending, lines, StandardCharsets.UTF_8);
            moveAtomically(pending, target);
        } finally {
            Files.deleteIfExists(pending);
        }
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
}
