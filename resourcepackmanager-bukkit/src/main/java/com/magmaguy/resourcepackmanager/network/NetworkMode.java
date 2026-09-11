package com.magmaguy.resourcepackmanager.network;

import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.config.DataConfig;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

/**
 * Detects whether RPM is running behind a proxy (Velocity / BungeeCord).
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
 *       BungeeCord IP-forwarding switch. Admin had to set it for forwarding
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

    /**
     * Cached result of {@link #getNetworkKey()}. The key is boot-stable
     * (Floodgate's {@code key.pem} only changes with a restart, matching how
     * the proxy plugins derive it exactly once at boot), and the supplier is
     * wired into per-request HTTP paths ({@code PackHttpServer}'s protected
     * executable route), so re-reading + re-hashing {@code key.pem} — or, on
     * Floodgate-less backends, re-hitting the generate-and-persist fallback —
     * on every request would be both wasteful and unsafe. Never invalidated;
     * like {@link #cached}, /reload re-initializes the static.
     */
    private static volatile String cachedNetworkKey;

    /**
     * Set once resolution has run, so an unkeyed backend does not re-resolve — and
     * re-warn — on every caller. Provisioning updates the key directly instead.
     */
    private static boolean resolvedOnce;

    private static volatile KeySource keySource = KeySource.NONE;

    /** Filename the proxy stores its key in; referenced in operator guidance. */
    private static final String NETWORK_KEY_FILENAME = "network-key";

    /** Where this backend's key came from. */
    public enum KeySource {
        /** Loaded from data.yml — every boot after the first. */
        PERSISTED,
        /** Adopted once from Floodgate's key, on backends that happen to run Floodgate. */
        SEEDED_FROM_FLOODGATE,
        /** Granted by the proxy. The normal path for a backend without Floodgate. */
        PROVISIONED,
        /** No key yet. In proxy topology this means the backend is not linked. */
        NONE
    }

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

        // Signal 2: spigot.yml settings.bungeecord. Legacy BungeeCord forwarding.
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
     * Resolves the network key that links this backend with its proxy. The proxy owns
     * the key; a backend either already has it, seeds it once, or is given it.
     * <ol>
     *     <li>{@link DataConfig#getNetworkKey()} — persisted from a previous boot or
     *         from provisioning. The steady state.</li>
     *     <li>A one-time seed from {@code plugins/floodgate/key.pem}, when this backend
     *         happens to run Floodgate. Keeps networks that already work on the identity
     *         they already use.</li>
     *     <li>Nothing — return {@code null} and say so. The proxy grants a key on the
     *         first player join.</li>
     * </ol>
     *
     * <p>Earlier versions derived from {@code key.pem} first and, failing that, generated
     * a random UUID silently. That was built on the false premise that Floodgate needs its
     * key on every backend; it does not, so on the topology Floodgate itself documents —
     * Floodgate on the proxy only — every backend invented a key that could never match
     * the proxy, and nothing reported it.</p>
     *
     * @return the key, or {@code null} when this backend has not been provisioned yet
     */
    public static String getNetworkKey() {
        String cachedKey = cachedNetworkKey;
        if (cachedKey != null) return cachedKey;
        synchronized (NetworkMode.class) {
            if (cachedNetworkKey != null) return cachedNetworkKey;
            if (resolvedOnce) return null;
            resolvedOnce = true;
            cachedNetworkKey = resolveNetworkKey();
            return cachedNetworkKey;
        }
    }

    /** Where the current key came from. Surfaced by {@code /rspm status}. */
    public static KeySource getKeySource() {
        getNetworkKey();
        return keySource;
    }

    /**
     * Accepts a key granted by the proxy, persisting it so provisioning happens
     * exactly once per backend.
     *
     * <p>Ignored when this backend already holds a key. A grant must never be able
     * to silently re-point an established backend at a different network; changing
     * an existing key is an operator action, not something a message can do.</p>
     *
     * @return {@code true} when the key was adopted
     */
    public static synchronized boolean acceptProvisionedKey(String grantedKey) {
        if (grantedKey == null || grantedKey.isBlank()) return false;
        resolvedOnce = true;
        if (cachedNetworkKey != null) {
            if (!cachedNetworkKey.equals(grantedKey)) {
                Logger.warn("A proxy offered a network key that differs from the one this backend already uses. "
                        + "Keeping the existing key. If two proxies front this backend they must share one key; "
                        + "copy " + NETWORK_KEY_FILENAME + " from the primary proxy to the other.");
            }
            return false;
        }
        DataConfig.setNetworkKey(grantedKey);
        cachedNetworkKey = grantedKey;
        keySource = KeySource.PROVISIONED;
        Logger.info("Network key received from the proxy; this backend is now linked.");
        return true;
    }

    private static String resolveNetworkKey() {
        // 1. Persisted key — the steady state. Covers keys provisioned by the proxy
        //    and keys seeded on a previous boot.
        String persisted = DataConfig.getNetworkKey();
        if (persisted != null && !persisted.isBlank()) {
            keySource = KeySource.PERSISTED;
            return persisted;
        }

        // 2. One-time seed from Floodgate's key.pem, matching how proxies adopt their
        //    identity on upgrade. Only reached on backends that actually run Floodgate;
        //    it is optional there, which is exactly why this can no longer be the
        //    primary path. See NetworkKeyAuthority for the full reasoning.
        java.nio.file.Path keyPem = ResourcePackManager.plugin.getDataFolder()
                .getParentFile().toPath()  // plugins/
                .resolve("floodgate")
                .resolve("key.pem");
        String seeded = com.magmaguy.resourcepackmanager.http.NetworkKeyResolver.deriveFromFloodgateKey(keyPem);
        if (seeded != null) {
            DataConfig.setNetworkKey(seeded);
            keySource = KeySource.SEEDED_FROM_FLOODGATE;
            return seeded;
        }

        // 3a. Standalone: this server is its own network, so it owns its key exactly as a
        //     proxy owns one. Minting is safe here because there is no proxy to match, and
        //     it preserves the identity standalone servers have always had — without this,
        //     they would silently stop uploading Bedrock artifacts to the relay.
        if (!isActive()) {
            String minted = com.magmaguy.resourcepackmanager.http.NetworkKeyResolver.mint();
            DataConfig.setNetworkKey(minted);
            keySource = KeySource.PERSISTED;
            return minted;
        }

        // 3b. Behind a proxy with nothing to use. Previously this invented a random UUID and
        //     returned it, which looked like a working key, could never match the proxy, and
        //     said nothing about it — the backend simply never linked. Report and wait to be
        //     provisioned instead.
        keySource = KeySource.NONE;
        Logger.warn("This backend is behind a proxy but has no network key yet, so it is not linked to the proxy.");
        Logger.warn("The proxy sends one automatically the first time a player connects to this server.");
        Logger.warn("If it never arrives, the proxy is missing ResourcePackManager or is an unsupported proxy;");
        Logger.warn("compare '/rspm status' on both sides — the network key fingerprints must match.");
        stageProxyInstallAssist();
        // When a Bedrock stack is present here, escalate to the dedicated,
        // Bedrock-specific banner — the plain lines above under-sell that
        // Bedrock players are the ones who break.
        if (ProxyLinkWarning.bedrockProxyLinkMissing()) {
            ProxyLinkWarning.warnConsole();
        }
        return null;
    }

    /**
     * Stages a byte-identical copy of this running jar for the proxy and says
     * exactly where to put it. Runs only on the unkeyed branch: once a grant
     * has been adopted the proxy plainly already has the plugin.
     */
    private static void stageProxyInstallAssist() {
        if (!(ResourcePackManager.plugin instanceof ResourcePackManager rspm)) return;
        File staged = ProxyInstallAssistant.stageProxyJar(rspm, rspm.pluginJarFile());
        if (staged == null) return;
        Logger.warn("A copy of this exact plugin jar has been staged for the proxy at:");
        Logger.warn("  " + staged.getAbsolutePath());
        Logger.warn("Copy that one file into the proxy's plugins folder (Velocity and BungeeCord");
        Logger.warn("use the same jar) and restart the proxy; the network key is then exchanged automatically.");
    }
}
