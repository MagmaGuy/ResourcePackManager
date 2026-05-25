package com.magmaguy.resourcepackmanager.proxy;

import java.util.List;

/**
 * Platform-neutral source of the proxy's currently-registered backend servers.
 * Velocity and Bungee implementations expose the proxy's server list (from
 * {@code velocity.toml} / {@code config.yml}) as a list of {@link Backend}
 * records that {@link NetworkSync} can hit at
 * {@code http://<host>:<httpPort>/bedrock.zip} and
 * {@code http://<host>:<httpPort>/mappings.json}.
 *
 * <p>HTTP port derivation: the backend's HTTP port is computed per-backend as
 * {@code mcPort() + networkHttpOffset} (default offset 100). This auto-staggers
 * HTTP ports across backends on a single host (each backend already has a unique
 * MC port). It also works fine cross-host (each backend has a unique MC port on
 * its own box).</p>
 */
public interface BackendListProvider {

    List<Backend> listBackends();

    /**
     * @param name   the backend's name as configured on the proxy (used for log
     *               identification; not part of the URL)
     * @param host   the backend's host (DNS name or IP) — same value the proxy
     *               uses to connect Minecraft players
     * @param mcPort the backend's Minecraft server port. {@link NetworkSync}
     *               adds {@code networkHttpOffset} to derive the HTTP port.
     */
    record Backend(String name, String host, int mcPort) {
    }
}
