package com.magmaguy.resourcepackmanager.http;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Derives the RPM network-key from a shared secret already established across
 * the network: Floodgate's {@code key.pem}. Same {@code key.pem} on every
 * component → same network-key on every component, zero admin config.
 *
 * <p>Separate Floodgate networks have different {@code key.pem} files, which
 * yield different network-keys automatically — cross-network isolation falls
 * out for free.</p>
 *
 * <p>The output is formatted as a UUID (using the first 128 bits of the SHA-256)
 * for symmetry with the previous auto-generated UUID network-keys.</p>
 */
public final class NetworkKeyResolver {

    private NetworkKeyResolver() {}

    /**
     * Try to derive a network-key by hashing the given Floodgate {@code key.pem}.
     * Returns {@code null} when the file doesn't exist or can't be read; caller
     * is expected to fall back to an alternative resolution path (admin override,
     * persisted UUID, etc.).
     */
    public static String deriveFromFloodgateKey(Path keyPemPath) {
        if (keyPemPath == null || !Files.isRegularFile(keyPemPath)) return null;
        try {
            byte[] bytes = Files.readAllBytes(keyPemPath);
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest(bytes);
            long msb = 0L, lsb = 0L;
            for (int i = 0; i < 8; i++)  msb = (msb << 8) | (hash[i] & 0xff);
            for (int i = 8; i < 16; i++) lsb = (lsb << 8) | (hash[i] & 0xff);
            return new UUID(msb, lsb).toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            return null;
        }
    }

    /**
     * 32-character SHA-256 hex prefix of the raw network key. Used as the URL
     * path component when downloading from the Bedrock relay
     * ({@code GET /rsp/bedrock/file/<hash>/<backendId>/<kind>}) — the hoster
     * stores files keyed by this hash and never sees / never stores the raw
     * key on disk.
     *
     * <p>Returns {@code null} on null / too-short input so callers can detect
     * malformed keys before issuing a request.</p>
     */
    public static String shortHashForRelay(String rawKey) {
        if (rawKey == null || rawKey.length() < 8) return null;
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest(rawKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }
}
