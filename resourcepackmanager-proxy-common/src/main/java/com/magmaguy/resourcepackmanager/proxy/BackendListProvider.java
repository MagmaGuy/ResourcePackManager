package com.magmaguy.resourcepackmanager.proxy;

import java.util.List;

/**
 * Platform-neutral source of the proxy's currently-registered backend servers.
 * Velocity and Bungee implementations expose the proxy's server list (from
 * {@code velocity.toml} / {@code config.yml}) as a list of {@link Backend}
 * records that {@link BackendMetadataPoller} can hit at
 * {@code http://<host>:<metadataPort>/.rspm-pack-info.json}.
 *
 * <p>Note: the Java pack-push port is irrelevant here. The poller targets the
 * backend's {@code self-host-port} (default 25567) — that's the port the
 * backend's always-on {@code PackHttpServer} listens on, which is distinct
 * from the Minecraft server port read from this list. We reuse the backend's
 * Minecraft host but substitute the metadata port; admins can override the
 * default via the proxy plugin's {@code backend-metadata-port} config.</p>
 */
public interface BackendListProvider {

    List<Backend> listBackends();

    /**
     * @param name the backend's name as configured on the proxy (used for log
     *             identification; not part of the URL)
     * @param host the backend's host (DNS name or IP) — same value the proxy
     *             uses to connect Minecraft players
     */
    record Backend(String name, String host) {
    }
}
