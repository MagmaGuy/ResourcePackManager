package com.magmaguy.resourcepackmanager.proxy;

import com.magmaguy.resourcepackmanager.http.NetworkKeyResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Establishes the proxy's network identity. The proxy is the authority: it owns
 * the key and hands it to backends, rather than every component independently
 * deriving one and hoping the results agree.
 *
 * <p>Resolution order:</p>
 * <ol>
 *     <li>the persisted {@code network-key} file — the steady state</li>
 *     <li>a one-time seed from Floodgate's {@code key.pem}, if present</li>
 *     <li>a freshly minted key</li>
 * </ol>
 *
 * <p>The seed exists so an upgrade is invisible. Every network that works today
 * derives its key from {@code key.pem}; seeding from the same file yields the
 * same value, so a proxy that upgrades before its backends stays linked to them
 * throughout the rollout. After the first boot the key is on disk and
 * {@code key.pem} is never read again.</p>
 *
 * <p>Unlike the previous implementation, a missing Floodgate file is not fatal.
 * It never should have been: Floodgate is a Bedrock authentication plugin, and
 * requiring it prevented Java-only networks from using RSPM's proxy features at
 * all.</p>
 *
 * <p>Deliberately logger-free so it stays platform-neutral and testable. The
 * caller inspects {@link Resolution} and logs in its own platform's voice.</p>
 */
public final class NetworkKeyAuthority {

    /** File under the proxy's data directory holding the raw key. */
    public static final String KEY_FILENAME = "network-key";

    private NetworkKeyAuthority() {
    }

    /** Where a resolved key came from. Surfaced in {@code /rspm status}. */
    public enum Source {
        /** Read from disk — every boot after the first. */
        PERSISTED,
        /** Adopted once from Floodgate's key so an existing network keeps its identity. */
        SEEDED_FROM_FLOODGATE,
        /** Newly generated, because there was nothing to persist or seed from. */
        MINTED
    }

    /** Outcome of resolution: the key, where it came from, and whether it survived to disk. */
    public static final class Resolution {
        private final String key;
        private final Source source;
        private final boolean persisted;
        private final String persistenceError;

        private Resolution(String key, Source source, boolean persisted, String persistenceError) {
            this.key = key;
            this.source = source;
            this.persisted = persisted;
            this.persistenceError = persistenceError;
        }

        public String key() {
            return key;
        }

        public Source source() {
            return source;
        }

        /**
         * @return {@code false} when a new key could not be written. The caller must
         *         warn loudly: an unpersisted key is regenerated on the next boot,
         *         which silently unlinks every backend that was already provisioned.
         */
        public boolean persisted() {
            return persisted;
        }

        /** Failure detail for the operator, or {@code null} when nothing failed. */
        public String persistenceError() {
            return persistenceError;
        }
    }

    /**
     * Resolves this proxy's network key, persisting it when newly established.
     *
     * @param dataDir          the proxy plugin's data directory
     * @param floodgateKeyPem  path to {@code plugins/floodgate/key.pem}; may be absent
     */
    public static Resolution resolve(Path dataDir, Path floodgateKeyPem) {
        Path keyFile = dataDir.resolve(KEY_FILENAME);

        String existing = readKey(keyFile);
        if (existing != null) return new Resolution(existing, Source.PERSISTED, true, null);

        String seeded = floodgateKeyPem == null
                ? null
                : NetworkKeyResolver.deriveFromFloodgateKey(floodgateKeyPem);
        String key = seeded != null ? seeded : NetworkKeyResolver.mint();
        Source source = seeded != null ? Source.SEEDED_FROM_FLOODGATE : Source.MINTED;

        String error = writeKey(dataDir, keyFile, key);
        return new Resolution(key, source, error == null, error);
    }

    /**
     * Reads a persisted key. Blank or malformed content is treated as absent so a
     * truncated write is re-established rather than propagated to every backend.
     */
    private static String readKey(Path keyFile) {
        try {
            if (!Files.isRegularFile(keyFile)) return null;
            String raw = new String(Files.readAllBytes(keyFile), StandardCharsets.UTF_8).trim();
            return isWellFormed(raw) ? raw : null;
        } catch (IOException exception) {
            return null;
        }
    }

    private static String writeKey(Path dataDir, Path keyFile, String key) {
        try {
            Files.createDirectories(dataDir);
            Files.write(keyFile, (key + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            return null;
        } catch (IOException | RuntimeException exception) {
            return exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage();
        }
    }

    /** UUID shape check — matches both minted keys and every previously derived key. */
    private static boolean isWellFormed(String candidate) {
        if (candidate == null || candidate.length() != 36) return false;
        try {
            java.util.UUID.fromString(candidate);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
