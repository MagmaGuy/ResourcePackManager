package com.magmaguy.resourcepackmanager.bungee;

import com.magmaguy.resourcepackmanager.proxy.BackendListProvider;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Bungee/Waterfall adapter for {@link BackendListProvider}. Reads
 * {@code ProxyServer#getServers()} on every poll cycle so backends added or
 * removed via Bungee's config reload pick up automatically.
 *
 * <p>Bungee's {@link ServerInfo#getSocketAddress()} can technically be a Unix
 * domain socket on modern versions, but in practice for normal multi-backend
 * deployments it's always an {@code InetSocketAddress}. We unwrap defensively
 * via {@code toString} fallback so a non-Inet socket doesn't blow up the whole
 * poll cycle.</p>
 */
final class BungeeBackendListProvider implements BackendListProvider {

    private final Plugin plugin;

    BungeeBackendListProvider(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<Backend> listBackends() {
        List<Backend> out = new ArrayList<>();
        for (ServerInfo info : plugin.getProxy().getServers().values()) {
            String name = info.getName();
            HostPort hp = extractHostPort(info);
            if (hp != null) out.add(new Backend(name, hp.host, hp.port));
        }
        return out;
    }

    private static HostPort extractHostPort(ServerInfo info) {
        java.net.SocketAddress sa = info.getSocketAddress();
        if (sa instanceof java.net.InetSocketAddress isa) {
            return new HostPort(isa.getHostString(), isa.getPort());
        }
        // Defensive: a non-Inet backend address (e.g. Unix domain socket) has no
        // routable hostname for an HTTP request. Skip rather than guess.
        return null;
    }

    private record HostPort(String host, int port) {}
}
