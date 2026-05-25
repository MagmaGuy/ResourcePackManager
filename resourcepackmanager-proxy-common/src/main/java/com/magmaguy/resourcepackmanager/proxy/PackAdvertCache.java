package com.magmaguy.resourcepackmanager.proxy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Proxy-side cache of {@code rspm:pack} advertisements received from backends
 * via the {@code rspm:pack} plugin-messaging channel. Used by
 * {@link GeyserBinder} as a fallback when {@link NetworkSync} hasn't produced
 * a network-merged pack yet (e.g. because the server-side
 * {@code /rsp/network/<key>/manifest} endpoint isn't shipped). Per-backend
 * entries are keyed by the backend's {@code rspUUID} from {@code DataConfig};
 * the most-recently-received entry is considered the "primary" and served as
 * the Bedrock fallback URL.
 *
 * <p>State is persisted to {@code <proxy-data>/known-backends.properties} so
 * a proxy restart doesn't wipe every entry before the first Java player
 * reconnects and re-advertises. Saves are best-effort; cache-only operation
 * keeps working even if disk I/O fails.</p>
 *
 * <p>Thread-safe: backed by a {@link ConcurrentHashMap}. The "primary" pointer
 * is recomputed on every {@link #register} call so {@link #getPrimary} is a
 * cheap volatile read.</p>
 */
public final class PackAdvertCache {

    /**
     * One advertisement entry. {@code sha1Bytes} may be empty (length 0) if the
     * backend somehow advertised without a known SHA-1 — Geyser doesn't strictly
     * need the hash from us since it downloads the pack file itself, but we
     * carry it through for completeness.
     */
    public record Advert(String backendUuid, String url, byte[] sha1Bytes) {
    }

    // Property-file keys: <backendUuid>.url and <backendUuid>.sha1
    private static final String URL_SUFFIX = ".url";
    private static final String SHA1_SUFFIX = ".sha1";

    private static final Map<String, Advert> ENTRIES = new ConcurrentHashMap<>();
    /** Monotonic counter so getPrimary always reflects the latest register() call. */
    private static final AtomicLong SEQ = new AtomicLong(0);
    private static final Map<String, Long> ENTRY_SEQ = new ConcurrentHashMap<>();
    private static volatile Advert primary;

    private PackAdvertCache() {
    }

    /**
     * Register or replace an advert for {@code backendUuid}. The most recently
     * registered entry (across all backends) becomes the primary returned by
     * {@link #getPrimary()}.
     */
    public static void register(String backendUuid, String url, byte[] sha1Bytes) {
        if (backendUuid == null || backendUuid.isBlank()) return;
        if (url == null || url.isBlank()) return;
        Advert entry = new Advert(backendUuid, url, sha1Bytes == null ? new byte[0] : sha1Bytes);
        ENTRIES.put(backendUuid, entry);
        ENTRY_SEQ.put(backendUuid, SEQ.incrementAndGet());
        primary = entry;
    }

    /** Returns the most recently registered advert, or {@code null} if the cache is empty. */
    public static Advert getPrimary() {
        return primary;
    }

    /** For tests: clear all entries. */
    static void clearForTesting() {
        ENTRIES.clear();
        ENTRY_SEQ.clear();
        primary = null;
        SEQ.set(0);
    }

    /**
     * Load previously-persisted entries from {@code persistFile}. Missing file is
     * not an error (first startup, or no backends have advertised yet). After load,
     * the {@link #getPrimary} pointer is set to the entry with the highest sequence
     * — which, on a cold load, is simply the last entry iterated; downstream
     * {@link #register} calls will quickly overwrite this with real run-time
     * advertisements, so the load-order ambiguity is harmless.
     */
    public static void load(Path persistFile) {
        if (persistFile == null) return;
        if (!Files.exists(persistFile)) return;
        Properties props = new Properties();
        try {
            try (var in = Files.newInputStream(persistFile)) {
                props.load(in);
            }
        } catch (IOException e) {
            // Disk read failed — start with an empty cache rather than crashing
            // proxy startup. The next inbound rspm:pack message will repopulate.
            return;
        }
        // Group key.url / key.sha1 pairs by backendUuid.
        Map<String, String[]> grouped = new ConcurrentHashMap<>();
        for (String key : props.stringPropertyNames()) {
            String value = props.getProperty(key);
            if (value == null) continue;
            if (key.endsWith(URL_SUFFIX)) {
                String uuid = key.substring(0, key.length() - URL_SUFFIX.length());
                grouped.computeIfAbsent(uuid, k -> new String[2])[0] = value;
            } else if (key.endsWith(SHA1_SUFFIX)) {
                String uuid = key.substring(0, key.length() - SHA1_SUFFIX.length());
                grouped.computeIfAbsent(uuid, k -> new String[2])[1] = value;
            }
        }
        for (Map.Entry<String, String[]> e : grouped.entrySet()) {
            String url = e.getValue()[0];
            String sha1Hex = e.getValue()[1];
            if (url == null || url.isBlank()) continue;
            byte[] sha1 = hexToBytes(sha1Hex);
            register(e.getKey(), url, sha1);
        }
    }

    /**
     * Persist the current cache to {@code persistFile}. Atomic enough for our
     * purposes — we tolerate a torn write because the next live advert will
     * overwrite the file again. Best-effort; failure is logged by callers if
     * they care to wrap this call.
     */
    public static void save(Path persistFile) {
        if (persistFile == null) return;
        Properties props = new Properties();
        for (Map.Entry<String, Advert> e : ENTRIES.entrySet()) {
            Advert a = e.getValue();
            props.setProperty(e.getKey() + URL_SUFFIX, a.url());
            props.setProperty(e.getKey() + SHA1_SUFFIX, bytesToHex(a.sha1Bytes()));
        }
        try {
            Path parent = persistFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            try (var out = Files.newOutputStream(persistFile)) {
                props.store(out, "ResourcePackManager backend advertisement cache");
            }
        } catch (IOException ignored) {
            // disk-write failures are non-fatal — the in-memory cache still works
        }
    }

    // ------------------------------------------------------------------
    // Hex helpers — kept local to avoid pulling in a util module for two calls.
    // ------------------------------------------------------------------

    private static byte[] hexToBytes(String hex) {
        if (hex == null || hex.isEmpty()) return new byte[0];
        int len = hex.length();
        if ((len & 1) != 0) return new byte[0]; // malformed; ignore
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = Character.digit(hex.charAt(i), 16);
            int lo = Character.digit(hex.charAt(i + 1), 16);
            if (hi < 0 || lo < 0) return new byte[0];
            out[i / 2] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
