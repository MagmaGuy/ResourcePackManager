package com.magmaguy.resourcepackmanager.network;

import com.magmaguy.resourcepackmanager.ResourcePackManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Re-surfaces the {@link ProxyLinkWarning} to operators as they connect, so
 * the "proxy is missing RSPM" diagnosis never depends on someone having read
 * the one-shot startup console. Also detects the proxy-only-Floodgate topology
 * — no Bedrock plugin on the backend at all — the moment a Bedrock player
 * arrives through the proxy, which is the only backend-observable signal that
 * this is a Bedrock network.
 */
public class ProxyLinkWarningListener implements Listener {

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Proxy-only-Floodgate discovery: if this backend is proxied+unkeyed
        // and a Bedrock player just arrived (recognised purely from the
        // forwarded connection), latch that observation so every surface —
        // this session and later boots — knows Bedrock is in play here.
        if (NetworkMode.isActive()
                && NetworkMode.getKeySource() == NetworkMode.KeySource.NONE
                && ProxyLinkWarning.looksBedrock(player)) {
            ProxyLinkWarning.noteBedrockBehindUnlinkedProxy();
        }

        if (!ProxyLinkWarning.bedrockProxyLinkMissing()) return;
        boolean bedrock = ProxyLinkWarning.looksBedrock(player);
        // Ops always get it; a joining Bedrock player is the live symptom, so
        // warn an online op then too even if that Bedrock player is not one.
        if (!player.isOp() && !bedrock) return;
        final Player recipient = player.isOp() ? player : firstOnlineOpOrNull();
        if (recipient == null) return;
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!recipient.isOnline()) return;
                if (!ProxyLinkWarning.bedrockProxyLinkMissing()) return;
                ProxyLinkWarning.warnPlayer(recipient);
            }
        }.runTaskLater(ResourcePackManager.plugin, 60L);
    }

    private static Player firstOnlineOpOrNull() {
        return ResourcePackManager.plugin.getServer().getOnlinePlayers().stream()
                .filter(Player::isOp)
                .findFirst()
                .orElse(null);
    }
}
