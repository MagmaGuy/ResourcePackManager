package com.magmaguy.resourcepackmanager.network;

import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.config.DataConfig;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.UUID;

/**
 * Detects whether RPM is running behind a proxy (Velocity / BungeeCord / Waterfall).
 * In that topology the backend is NOT responsible for client-facing pack delivery —
 * the proxy plugin handles it. RPM still mixes its own plugin pack and uploads it;
 * the proxy fetches and merges as part of the network workflow.
 *
 * <h2>Detection signals (any one of these is sufficient)</h2>
 * <ol>
 *   <li><b>Floodgate present, Geyser-Spigot absent</b> — strongest signal for the
 *       Bedrock-via-proxy case: Floodgate only makes sense if there's a Geyser
 *       somewhere, and if Geyser isn't on this backend, it's on the proxy.</li>
 *   <li><b>{@code spigot.yml}: {@code settings.bungeecord: true}</b> — the legacy
 *       BungeeCord / Waterfall IP-forwarding switch. Admin had to set it for forwarding
 *       to work at all, so seeing it true is a definitive "yes, behind a proxy."</li>
 *   <li><b>{@code paper-global.yml}: {@code proxies.velocity.enabled: true}</b> —
 *       modern Velocity forwarding flag. Same logic as #2 for Velocity setups.</li>
 * </ol>
 *
 * <p>Why combine signals: signal #1 alone misses Java-only proxy setups where Floodgate
 * isn't installed. Signals #2/#3 catch those. Conversely, signal #1 catches networks
 * that use modern forwarding but where the admin hasn't enabled the corresponding
 * Paper/Spigot config (rare but possible). Combining them gets us near-100% recall
 * with near-zero false positives.
 *
 * <p>Result is cached after the first check; assumes the proxy topology doesn't change
 * mid-session. Caching is safe across /reload because the static field is re-initialized
 * when the class is reloaded.
 */
public final class NetworkMode {

    private static Boolean cached;

    private NetworkMode() {}

    public static boolean isActive() {
        if (cached != null) return cached;
        cached = detectProxyTopology();
        return cached;
    }

    private static boolean detectProxyTopology() {
        // Signal 1: Bedrock-via-proxy heuristic (current behavior, kept).
        boolean noGeyser = Bukkit.getPluginManager().getPlugin("Geyser-Spigot") == null;
        boolean floodgate = Bukkit.getPluginManager().getPlugin("floodgate") != null;
        if (noGeyser && floodgate) return true;

        // Signal 2: spigot.yml settings.bungeecord. Legacy BungeeCord/Waterfall forwarding.
        // Reading the file directly (not via Bukkit.spigot()) keeps this Spigot-API-clean
        // for environments where the API surface is restricted.
        if (readBooleanFromYaml(new File("spigot.yml"), "settings.bungeecord", false)) return true;

        // Signal 3: paper-global.yml proxies.velocity.enabled. Modern forwarding.
        // Paper stores this under config/ on newer versions, and at the root on older.
        // Try both locations.
        if (readBooleanFromYaml(new File("config/paper-global.yml"), "proxies.velocity.enabled", false)) return true;
        if (readBooleanFromYaml(new File("paper-global.yml"), "proxies.velocity.enabled", false)) return true;

        return false;
    }

    /**
     * Best-effort YAML boolean read using Bukkit's bundled snakeyaml-backed
     * YamlConfiguration. Returns the default on any failure (file missing,
     * parse error, wrong type) — this is detection logic; a parse failure
     * shouldn't crash plugin enable.
     */
    private static boolean readBooleanFromYaml(File file, String dottedPath, boolean defaultValue) {
        if (file == null || !file.isFile()) return defaultValue;
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            return yaml.getBoolean(dottedPath, defaultValue);
        } catch (Throwable t) {
            return defaultValue;
        }
    }

    /**
     * Resolves the network key that links this backend with the proxy plugin and any
     * other backends in the same network. Resolution order (no admin override path —
     * the manual-paste workflow was retired because typos in the pasted key silently
     * broke the proxy↔backend link; see DefaultConfig comment for why):
     * <ol>
     *     <li>Derive from {@code plugins/floodgate/key.pem} —
     *         {@link com.magmaguy.resourcepackmanager.http.NetworkKeyResolver#deriveFromFloodgateKey}.
     *         Floodgate REQUIRES this file to be the same on every backend and on the
     *         proxy for Bedrock players to connect at all, so the derived key matches
     *         everywhere automatically. Zero admin config.</li>
     *     <li>{@link DataConfig#getNetworkKey()} — value persisted from a previous boot.
     *         Only reached when Floodgate isn't installed; lets a backend continue to
     *         report a stable identity in network mode even though the proxy can't
     *         match it.</li>
     *     <li>Auto-generate a fresh {@link UUID}, persist it to {@code data.yml}, return it.
     *         Reached only when Floodgate is missing AND there's no prior persisted key.
     *         In this state the proxy and backend WILL NOT link — operator must install
     *         Floodgate on both sides. The plugin still boots and runs in standalone-pack
     *         mode so the operator can fix Floodgate and reload.</li>
     * </ol>
     */
    public static String getNetworkKey() {
        // 1. Derive from Floodgate key.pem — the canonical path.
        //    plugins/floodgate/key.pem (same on every backend AND proxy that talks
        //    to the same network — Floodgate requires this for Bedrock auth).
        java.nio.file.Path keyPem = ResourcePackManager.plugin.getDataFolder()
                .getParentFile().toPath()  // plugins/
                .resolve("floodgate")
                .resolve("key.pem");
        String derived = com.magmaguy.resourcepackmanager.http.NetworkKeyResolver.deriveFromFloodgateKey(keyPem);
        if (derived != null) return derived;

        // 2. Fallback: persisted UUID, auto-generated on first call.
        String persisted = DataConfig.getNetworkKey();
        if (persisted != null && !persisted.isBlank()) return persisted;
        String generated = UUID.randomUUID().toString();
        DataConfig.setNetworkKey(generated);
        return generated;
    }
}
