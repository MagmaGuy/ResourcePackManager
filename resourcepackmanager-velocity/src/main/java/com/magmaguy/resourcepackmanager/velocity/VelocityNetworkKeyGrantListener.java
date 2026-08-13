package com.magmaguy.resourcepackmanager.velocity;

import com.magmaguy.resourcepackmanager.http.NetworkKeyGrantSignature;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Hands this proxy's network key to each backend as players connect to it.
 *
 * <p>The proxy owns the key; backends receive it and persist it. The push direction is
 * deliberate and explained on {@link #provision(ServerConnection)}.</p>
 */
public final class VelocityNetworkKeyGrantListener {

    /** Must match the backend's channel exactly. */
    public static final MinecraftChannelIdentifier CHANNEL =
            MinecraftChannelIdentifier.from("rspm:network");

    private static final String GRANT = "KEY_GRANT";

    /** Backends we have already announced once, purely to keep the log readable. */
    private final java.util.Set<String> announced = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final ProxyServer proxy;
    private final Logger slf4j;
    private final String networkKey;
    /** HMAC of the key under the forwarding secret, or null on legacy forwarding. */
    private final String signature;

    public VelocityNetworkKeyGrantListener(ProxyServer proxy, Logger slf4j, String networkKey,
                                           Path forwardingSecretFile) {
        this.proxy = proxy;
        this.slf4j = slf4j;
        this.networkKey = networkKey;
        String secret = NetworkKeyGrantSignature.readProxyForwardingSecret(forwardingSecretFile);
        this.signature = NetworkKeyGrantSignature.sign(secret, networkKey);
    }

    /** Registers the channel so Velocity will carry it on backend connections. */
    public void register() {
        proxy.getChannelRegistrar().register(CHANNEL);
        slf4j.info("[RSPM] Network key provisioning ready on {}; backends are keyed as players connect.",
                CHANNEL.getId());
        if (signature == null) {
            // Expected on legacy forwarding; worth stating because a backend that DOES hold a
            // secret will reject unsigned grants, and this line explains why in one look.
            slf4j.info("[RSPM] No forwarding secret available, so grants are unsigned. "
                    + "Backends configured for Velocity modern forwarding will reject them.");
        }
    }

    /**
     * Pushes the key to a backend as soon as a player lands on it.
     *
     * <p><b>Why the proxy pushes instead of answering a request:</b> a backend can only
     * speak to the proxy by sending a <em>clientbound</em> plugin message through a
     * player, and Velocity refuses to forward — or raise an event for — any non-{@code
     * minecraft:} channel the player's client has not itself registered
     * ({@code canForwardPluginMessage} consults the client's known channels on 1.13+).
     * A vanilla client never registers this channel, so a backend request is dropped
     * before any plugin sees it. Verified empirically: a catch-all handler logging every
     * plugin message on every channel never fired once.</p>
     *
     * <p>The proxy→backend direction has no such gate: it writes straight to the backend
     * connection. That connection already exists in every topology, including the normal
     * case where proxy and backends are on different machines, so nothing new is exposed
     * on the wire.</p>
     */
    public void provision(ServerConnection backend) {
        if (backend == null || networkKey == null || networkKey.isBlank()) return;
        String name = backend.getServerInfo().getName();
        // Sent on every connect rather than once per backend. A backend that already has a
        // key discards this, so the cost is one tiny message; the benefit is that a grant
        // rejected earlier — a mismatched forwarding secret, say — is retried as soon as the
        // operator fixes it, instead of requiring a proxy restart to re-arm.
        String payload = signature == null
                ? GRANT + ":" + networkKey
                : GRANT + ":" + networkKey + ":" + signature;
        backend.sendPluginMessage(CHANNEL, payload.getBytes(StandardCharsets.UTF_8));
        // Debug, not info: this now fires on every connect, and an operator does not need
        // a log line per player. The backend states plainly when it actually adopts a key.
        slf4j.debug("[RSPM] Sent the network key to backend '{}'{}.", name,
                signature == null ? "" : " (signed with the forwarding secret)");
        if (announced.add(name)) {
            slf4j.info("[RSPM] Offered the network key to backend '{}'{}.", name,
                    signature == null ? " (unsigned)" : " (signed with the forwarding secret)");
        }
    }
}
