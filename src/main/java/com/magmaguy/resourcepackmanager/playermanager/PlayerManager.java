package com.magmaguy.resourcepackmanager.playermanager;

import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.autohost.AutoHost;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class PlayerManager implements Listener {

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        AutoHost.sendResourcePack(event.getPlayer());
    }
}
