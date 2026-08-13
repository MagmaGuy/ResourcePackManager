package com.magmaguy.resourcepackmanager.http;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Produces the RSPM network-key: the value that links a proxy with its backends
 * and namespaces the network's storage on the relay.
 *
 * <p><b>The proxy owns this value.</b> It mints one with {@link #mint()} and
 * hands it to backends. Floodgate is no longer consulted at runtime.</p>
 *
 * <p>{@link #deriveFromFloodgateKey(Path)} survives only as a one-time
 * <em>migration seed</em>. Earlier versions derived the key by hashing
 * Floodgate's {@code key.pem}, on the incorrect premise that Floodgate requires
 * that file on every backend. It does not — <a
 * href="https://geysermc.org/wiki/floodgate/setup/">Floodgate's own setup
 * guide</a> states you only need Floodgate on the proxy unless you want its API
 * on backends. On that documented default topology the old scheme could never
 * link, because backends silently generated a random key instead.</p>
 *
 * <p>Seeding from {@code key.pem} on first boot keeps every already-working
 * network on the identity it currently uses, so a proxy that upgrades before its
 * backends stays linked to them mid-rollout. Once a component has persisted a
 * key, this method is never called again, and the seed branch can be deleted
 * outright in a later version.</p>
 *
 * <p>Both forms are UUID-shaped so persisted keys from any era remain valid.</p>
 */
public final class NetworkKeyResolver {

    private NetworkKeyResolver() {}

    /**
     * Mints a fresh network-key.
     *
     * <p>Called once per proxy, on the first boot that finds no persisted key and
     * no Floodgate file to seed from. A random UUID is a stronger credential than
     * the old derived value: because the relay treats the key itself as the
     * credential, deriving it from {@code key.pem} meant leaking that file also
     * handed over the network's relay tenancy. Minting decouples the two.</p>
     */
    public static String mint() {
        return UUID.randomUUID().toString();
    }

    /**
     * Derives a network-key by hashing the given Floodgate {@code key.pem}.
     *
     * <p><b>Migration seed only</b> — see the class docs. Returns {@code null}
     * when the file is absent or unreadable, which is a normal, supported state
     * and means the caller should mint (proxy) or request provisioning
     * (backend), never silently invent a key.</p>
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
