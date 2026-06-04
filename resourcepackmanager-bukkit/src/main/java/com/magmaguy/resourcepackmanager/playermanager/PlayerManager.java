package com.magmaguy.resourcepackmanager.playermanager;

import com.magmaguy.resourcepackmanager.autohost.AutoHost;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

public class PlayerManager implements Listener {

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Don't push the pack synchronously on the join tick — the client isn't
        // ready to accept it yet and silently drops it (textures stay missing
        // until a later resend). AutoHost defers the send so it lands on a
        // settled client. See AutoHost#scheduleJoinSend.
        AutoHost.scheduleJoinSend(event.getPlayer());
    }

    @EventHandler
    public void onPlayerResourcePackStatus(PlayerResourcePackStatusEvent event) {
        // Self-heal: if the client reports the pack failed, re-push it a bounded
        // number of times instead of leaving the player to run /rspm reload.
        AutoHost.handleResourcePackStatus(event);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        AutoHost.forgetPlayer(event.getPlayer().getUniqueId());
    }
}
