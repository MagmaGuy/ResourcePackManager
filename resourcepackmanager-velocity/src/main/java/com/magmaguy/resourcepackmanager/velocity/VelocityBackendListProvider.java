package com.magmaguy.resourcepackmanager.velocity;

import com.magmaguy.resourcepackmanager.proxy.BackendListProvider;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.ArrayList;
import java.util.List;

/**
 * Velocity adapter for {@link BackendListProvider}. Reads
 * {@link ProxyServer#getAllServers()} on every poll cycle so backends added or
 * removed via {@code /server add}/{@code velocity.toml} reload pick up
 * automatically without restarting the plugin.
 */
final class VelocityBackendListProvider implements BackendListProvider {

    private final ProxyServer proxy;

    VelocityBackendListProvider(ProxyServer proxy) {
        this.proxy = proxy;
    }

    @Override
    public List<Backend> listBackends() {
        List<Backend> out = new ArrayList<>();
        for (RegisteredServer rs : proxy.getAllServers()) {
            String name = rs.getServerInfo().getName();
            String host = rs.getServerInfo().getAddress().getHostString();
            int mcPort = rs.getServerInfo().getAddress().getPort();
            out.add(new Backend(name, host, mcPort));
        }
        return out;
    }
}
