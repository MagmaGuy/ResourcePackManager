package com.magmaguy.resourcepackmanager.network;

import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.config.DataConfig;
import com.magmaguy.resourcepackmanager.config.DefaultConfig;
import org.bukkit.Bukkit;

import java.util.UUID;

/**
 * Detects whether RPM is running behind a proxy (Velocity / BungeeCord / Waterfall)
 * that hosts Geyser, with only Floodgate present on this backend. In that topology
 * the backend is NOT responsible for client-facing pack delivery — the proxy plugin
 * handles it. RPM still mixes its own plugin pack and uploads it; the proxy fetches
 * and merges as part of the network workflow.
 *
 * Detection rule: Floodgate plugin is loaded AND Geyser-Spigot is NOT. If Geyser-Spigot
 * runs on this backend, we're a standalone server (or behind a Geyser-on-proxy that
 * also happens to run Geyser on the backend, which is unusual) and we use the legacy
 * single-server pack delivery path.
 *
 * Result is cached after the first check; assumes the proxy topology doesn't change
 * mid-session. Caching is safe across /reload because the static field is re-initialized
 * when the class is reloaded.
 */
public final class NetworkMode {

    private static Boolean cached;

    private NetworkMode() {}

    public static boolean isActive() {
        if (cached != null) return cached;
        boolean noGeyser = Bukkit.getPluginManager().getPlugin("Geyser-Spigot") == null;
        boolean floodgate = Bukkit.getPluginManager().getPlugin("floodgate") != null;
        cached = noGeyser && floodgate;
        return cached;
    }

    /**
     * Resolves the network key that links this backend with the proxy plugin and any
     * other backends in the same network. Resolution order:
     * <ol>
     *     <li>{@link DefaultConfig#getNetworkKey()} — admin override pinned in config.yml.</li>
     *     <li>Derived from {@code plugins/floodgate/key.pem} via
     *         {@link com.magmaguy.resourcepackmanager.http.NetworkKeyResolver#deriveFromFloodgateKey} —
     *         preferred default. Same key.pem on every component → same network-key,
     *         zero admin config.</li>
     *     <li>{@link DataConfig#getNetworkKey()} — value persisted from a previous boot.</li>
     *     <li>Auto-generate a fresh {@link UUID}, persist it to {@code data.yml}, return it.</li>
     * </ol>
     * Auto-generated keys are saved immediately (not deferred) so a crash before the
     * next config-save cycle doesn't lose the key the admin already pasted into the
     * proxy plugin.
     */
    public static String getNetworkKey() {
        // 1. Admin override
        String override = DefaultConfig.getNetworkKey();
        if (override != null && !override.isBlank()) return override;

        // 2. Derive from Floodgate key.pem — preferred default.
        //    plugins/floodgate/key.pem (same on every backend that talks to the same proxy)
        java.nio.file.Path keyPem = ResourcePackManager.plugin.getDataFolder()
                .getParentFile().toPath()  // plugins/
                .resolve("floodgate")
                .resolve("key.pem");
        String derived = com.magmaguy.resourcepackmanager.http.NetworkKeyResolver.deriveFromFloodgateKey(keyPem);
        if (derived != null) return derived;

        // 3. Fallback: persisted UUID, auto-generated on first call.
        String persisted = DataConfig.getNetworkKey();
        if (persisted != null && !persisted.isBlank()) return persisted;
        String generated = UUID.randomUUID().toString();
        DataConfig.setNetworkKey(generated);
        return generated;
    }
}
