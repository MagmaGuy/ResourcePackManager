package com.magmaguy.resourcepackmanager.network;

import org.bukkit.Bukkit;

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
}
