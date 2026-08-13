package com.magmaguy.resourcepackmanager.bungee;

import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;

import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * Hands this proxy's network key to each backend as players connect to it.
 *
 * <p>Same push model as the Velocity side, and for the same reason: a backend's only
 * route to the proxy is a clientbound plugin message, which proxies drop for channels
 * the player's client has not registered. Pushing writes straight to the backend
 * connection, which exists in every topology including proxy and backends on separate
 * machines.</p>
 *
 * <p>Bungee has no shared secret to sign the grant with — {@code ip_forward} is a plain
 * boolean — so this rests on the assumption Bungee networks already make: that backends
 * are reachable only by their proxy. It adds no trust that {@code ip_forward} does not
 * already require.</p>
 */
public final class BungeeNetworkKeyGrantListener implements Listener {

    /** Must match the backend's channel exactly. */
    public static final String CHANNEL = "rspm:network";

    private static final String GRANT = "KEY_GRANT";

    /** Backends we have already announced once, purely to keep the log readable. */
    private final java.util.Set<String> announced = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final Plugin plugin;
    private final Logger logger;
    private final String networkKey;

    public BungeeNetworkKeyGrantListener(Plugin plugin, Logger logger, String networkKey) {
        this.plugin = plugin;
        this.logger = logger;
        this.networkKey = networkKey;
    }

    /** Registers the channel and this listener. */
    public void register() {
        plugin.getProxy().registerChannel(CHANNEL);
        plugin.getProxy().getPluginManager().registerListener(plugin, this);
        logger.info("[RSPM] Network key provisioning ready on " + CHANNEL
                + "; backends are keyed as players connect.");
    }

    @EventHandler
    public void onServerConnected(ServerConnectedEvent event) {
        Server backend = event.getServer();
        if (backend == null || networkKey == null || networkKey.isBlank()) return;
        ServerInfo info = backend.getInfo();
        String name = info == null ? "unknown" : info.getName();
        // Sent on every connect rather than once per backend: a keyed backend discards it,
        // and a grant that was rejected earlier gets retried once the operator fixes the cause.
        backend.sendData(CHANNEL, (GRANT + ":" + networkKey).getBytes(StandardCharsets.UTF_8));
        // Announced once per backend so the log stays readable; the send itself is per connect.
        if (announced.add(name)) {
            logger.info("[RSPM] Offered the network key to backend '" + name + "' (unsigned).");
        }
    }
}
