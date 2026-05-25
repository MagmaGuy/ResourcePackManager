package com.magmaguy.resourcepackmanager.network;

import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.http.MagmaguyRspClient;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;

/**
 * Periodically polls {@code /rsp/network/<key>/manifest} so the backend knows the
 * current network-merged pack URL + sha1 to push to Java clients.
 *
 * <p>Backends in network mode push the SAME network-merged URL via
 * {@link com.magmaguy.resourcepackmanager.autohost.AutoHost#sendResourcePack} that
 * the proxy plugin would have pushed in the old design. Since 1.20.3+ Java clients
 * dedupe repeat pushes of the same {@code (URL, sha1, UUID)} as a no-op, every
 * backend can push the merged URL at PlayerJoinEvent and the client sees exactly
 * one pack prompt at first backend connect — no re-prompts on {@code /server}.</p>
 *
 * <p><b>Graceful degradation:</b> the server-side
 * {@code /rsp/network/<key>/manifest} endpoint is not shipped yet. While it isn't,
 * {@link MagmaguyRspClient#fetchNetworkManifest} throws
 * {@link UnsupportedOperationException}; we catch it, log a single INFO line, and
 * leave the cached URL {@code null}. {@code AutoHost.sendResourcePack} then falls
 * back to pushing the backend's own pack URL (divergent across backends — clients
 * re-prompt on {@code /server} — but functional). Once the server ships the
 * endpoint, backends pick up the merged URL automatically on the next poll.</p>
 */
public final class NetworkManifestPoll {

    private static volatile String cachedMergedUrl;
    private static volatile byte[] cachedMergedSha1Bytes;
    private static volatile String cachedMergedSha1Hex;
    private static BukkitTask task;
    private static boolean loggedStubWarning = false;

    private NetworkManifestPoll() {
    }

    /**
     * Schedule the poll task. No-op outside network mode or if already started.
     */
    public static void start(MagmaguyRspClient client) {
        if (!NetworkMode.isActive()) return;
        if (task != null) return;
        if (client == null) return;
        // 20 ticks/sec * 60s = 1200 ticks between polls.
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(
                ResourcePackManager.plugin,
                () -> poll(client),
                20L,
                20L * 60L);
    }

    /**
     * Cancel the poll task and clear cached state. Called from
     * {@link com.magmaguy.resourcepackmanager.autohost.AutoHost#shutdown()}.
     */
    public static void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        cachedMergedUrl = null;
        cachedMergedSha1Bytes = null;
        cachedMergedSha1Hex = null;
        loggedStubWarning = false;
    }

    /** Most recent network-merged URL, or {@code null} if not yet known. */
    public static String getCachedMergedUrl() {
        return cachedMergedUrl;
    }

    /** Most recent network-merged sha1 (raw bytes), or {@code null} if not yet known. */
    public static byte[] getCachedMergedSha1Bytes() {
        return cachedMergedSha1Bytes;
    }

    /** Most recent network-merged sha1 (lowercase hex), or {@code null} if not yet known. */
    public static String getCachedMergedSha1Hex() {
        return cachedMergedSha1Hex;
    }

    private static void poll(MagmaguyRspClient client) {
        try {
            MagmaguyRspClient.ManifestResult manifest =
                    client.fetchNetworkManifest(NetworkMode.getNetworkKey());
            if (manifest == null) return;
            MagmaguyRspClient.ManifestResult.Entry merged = pickMergedEntry(manifest.entries());
            if (merged == null) {
                // Manifest came back but we couldn't identify the merged entry.
                // Leave the cache as-is so existing players keep getting served.
                return;
            }
            String url = merged.url();
            String sha1Hex = merged.sha1();
            if (url == null || sha1Hex == null) return;
            byte[] bytes = decodeHex(sha1Hex);
            if (bytes == null) return;
            cachedMergedUrl = url;
            cachedMergedSha1Hex = sha1Hex.toLowerCase();
            cachedMergedSha1Bytes = bytes;
        } catch (UnsupportedOperationException e) {
            if (!loggedStubWarning) {
                Logger.info("Network manifest endpoint not yet shipped on magmaguy.com — falling back to per-backend pack URLs for Java pack push.");
                loggedStubWarning = true;
            }
        } catch (Exception e) {
            Logger.warn("Failed to poll network manifest: " + e.getMessage());
        }
    }

    /**
     * Identify the merged-pack entry in a manifest. Server-contract is still
     * being finalized; for now we accept any entry whose uuid is the literal
     * string {@code "merged"} OR whose URL ends with {@code /merged}. The
     * proxy plugin uploads the merged pack via
     * {@code POST /rsp/network/<key>/merged} so the canonical URL ends in
     * {@code /merged}; backends can use that as a fallback discriminator until
     * the server contract pins down an explicit marker.
     */
    static MagmaguyRspClient.ManifestResult.Entry pickMergedEntry(
            List<MagmaguyRspClient.ManifestResult.Entry> entries) {
        if (entries == null || entries.isEmpty()) return null;
        for (MagmaguyRspClient.ManifestResult.Entry e : entries) {
            if ("merged".equalsIgnoreCase(e.uuid())) return e;
            String url = e.url();
            if (url != null && url.endsWith("/merged")) return e;
        }
        return null;
    }

    private static byte[] decodeHex(String hex) {
        if (hex == null) return null;
        String s = hex.trim();
        if (s.length() % 2 != 0) return null;
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(2 * i), 16);
            int lo = Character.digit(s.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
