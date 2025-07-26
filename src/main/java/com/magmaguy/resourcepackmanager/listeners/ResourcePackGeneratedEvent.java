package com.magmaguy.resourcepackmanager.listeners;

import com.magmaguy.freeminecraftmodels.events.ResourcePackGenerationEvent;
import com.magmaguy.resourcepackmanager.commands.ReloadCommand;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class ResourcePackGeneratedEvent implements Listener {
    @EventHandler
    public void onResourcePackGenerated(ResourcePackGenerationEvent event) {
        ReloadCommand.reloadPlugin(Bukkit.getConsoleSender());
    }
}
