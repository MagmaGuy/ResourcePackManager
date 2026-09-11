package com.magmaguy.resourcepackmanager.network;

import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.http.NetworkKeyGrantSignature;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.nio.charset.StandardCharsets;

/**
 * Receives this backend's network key from the proxy.
 *
 * <p>A backend cannot derive the key on its own — that was the flaw in the previous
 * design — and it cannot ask for one either: its only route to the proxy is a
 * clientbound plugin message, which proxies drop for any channel the player's client
 * has not registered. So the proxy pushes instead, as soon as a player connects here.
 * The key is persisted, making this a once-per-backend event.</p>
 *
 * <p>Grants are only honoured while this backend holds no key. See
 * {@link NetworkMode#acceptProvisionedKey(String)}.</p>
 */
public final class NetworkKeyProvisioning implements Listener, PluginMessageListener {

    /** Kept separate from the Geyser entity bridge so the two cannot interfere. */
    public static final String CHANNEL = "rspm:network";

    /** Proxy → backend: "here is the network key." */
    private static final String GRANT = "KEY_GRANT";

    private static NetworkKeyProvisioning instance;

    private NetworkKeyProvisioning() {
    }

    public static void register() {
        if (instance != null || ResourcePackManager.plugin == null) return;
        // Only meaningful behind a proxy; a standalone server owns its own key.
        if (!NetworkMode.isActive()) return;

        instance = new NetworkKeyProvisioning();
        Bukkit.getMessenger().registerIncomingPluginChannel(ResourcePackManager.plugin, CHANNEL, instance);
        Bukkit.getPluginManager().registerEvents(instance, ResourcePackManager.plugin);
        // Stated explicitly: when a key never arrives, the first thing to establish is
        // whether this backend was even listening. Without this line that is unknowable.
        Logger.info("Network key provisioning armed on channel " + CHANNEL
                + "; the proxy sends the key when a player first connects to this server.");

    }

    public static void unregister() {
        if (instance == null || ResourcePackManager.plugin == null) return;
        HandlerList.unregisterAll(instance);
        Bukkit.getMessenger().unregisterIncomingPluginChannel(ResourcePackManager.plugin, CHANNEL, instance);
        instance = null;
    }

    /**
     * Handles the proxy's grant.
     *
     * <p>The {@code player} argument is the connection the message arrived on, not its
     * author: on a backend, plugin messages on a registered channel are delivered by the
     * proxy.</p>
     *
     * <p>When this backend is configured for Velocity modern forwarding it already holds
     * the forwarding secret, so it <em>requires</em> the grant to be signed with it. That
     * is what stops an attacker simply omitting the signature to dodge the check. Networks
     * without a secret — legacy forwarding and BungeeCord — accept an unsigned grant,
     * because no shared secret exists there to do better with.</p>
     */
    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel) || message == null) return;
        String payload = new String(message, StandardCharsets.UTF_8).trim();
        if (!payload.startsWith(GRANT + ":")) return;

        String body = payload.substring(GRANT.length() + 1).trim();
        int separator = body.indexOf(':');
        String grantedKey = separator < 0 ? body : body.substring(0, separator).trim();
        String presentedSignature = separator < 0 ? null : body.substring(separator + 1).trim();

        String secret = readForwardingSecret();
        if (secret != null && !NetworkKeyGrantSignature.verify(secret, grantedKey, presentedSignature)) {
            // The two causes need different fixes, so they get different messages. Telling a
            // BungeeCord operator to "match the forwarding secret" would be a wild goose chase:
            // Bungee has no such secret, and the real problem is a stale Velocity setting here.
            if (presentedSignature == null || presentedSignature.isBlank()) {
                Logger.warn("Rejected an unsigned network key from the proxy. This server has a Velocity "
                        + "forwarding secret set, so it requires signed keys, but the proxy sent none.");
                Logger.warn("On Velocity, switch player-info-forwarding-mode to modern. On BungeeCord, "
                        + "clear proxies.velocity.secret in paper-global.yml — that proxy "
                        + "has no forwarding secret and cannot sign.");
            } else {
                Logger.warn("Rejected a network key from the proxy: it was signed with a different "
                        + "forwarding secret than this server uses. Make the proxy's forwarding secret and "
                        + "this server's proxies.velocity.secret identical; until then it stays unlinked.");
            }
            return;
        }

        if (NetworkMode.acceptProvisionedKey(grantedKey)) {
            Logger.info("Run '/rspm status' here and on the proxy to confirm the fingerprints match.");
        }
    }

    /**
     * Reads this backend's Velocity forwarding secret, when modern forwarding is set up.
     * Paper stores it in {@code paper-global.yml}, under {@code config/} on current
     * versions and at the server root on older ones.
     *
     * @return the secret, or {@code null} on any topology that does not use one
     */
    private static String readForwardingSecret() {
        for (String path : new String[]{"config/paper-global.yml", "paper-global.yml"}) {
            File file = new File(path);
            if (!file.isFile()) continue;
            try {
                String secret = YamlConfiguration.loadConfiguration(file)
                        .getString("proxies.velocity.secret");
                if (secret != null && !secret.isBlank()) return secret;
            } catch (Throwable ignored) {
                // Detection logic; an unreadable config must not break provisioning.
            }
        }
        return null;
    }
}
