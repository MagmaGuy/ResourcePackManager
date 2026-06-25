package com.magmaguy.resourcepackmanager.proxy;

import java.util.List;

/**
 * Platform-neutral source of the proxy's currently-registered backend servers.
 * Velocity and Bungee implementations expose the proxy's server list (from
 * {@code velocity.toml} / {@code config.yml}) as a list of {@link Backend}
 * records that {@link NetworkSync} can hit for backend Bedrock assets.
 *
 * <p>HTTP port discovery: backends announce the ResourcePackManager HTTP port
 * they actually bound to the shared remote endpoint registry. The proxy matches
 * that announcement to this list and fetches
 * {@code http://<host>:<announcedPort>/bedrock.zip}. If an announcement is not
 * available yet, the proxy falls back to {@code mcPort() + networkHttpOffset}
 * for startup compatibility.</p>
 */
public interface BackendListProvider {

    List<Backend> listBackends();

    /**
     * @param name   the backend's name as configured on the proxy (used for log
     *               identification; not part of the URL)
     * @param host   the backend's host (DNS name or IP) — same value the proxy
     *               uses to connect Minecraft players
     * @param mcPort the backend's Minecraft server port, used to match endpoint
     *               announcements and to derive the fallback HTTP port before an
     *               announcement is available.
     */
    record Backend(String name, String host, int mcPort) {
    }
}
