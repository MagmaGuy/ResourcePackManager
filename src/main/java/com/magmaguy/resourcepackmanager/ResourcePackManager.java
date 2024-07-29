package com.magmaguy.resourcepackmanager;

import com.magmaguy.magmacore.MagmaCore;
import com.magmaguy.magmacore.command.CommandManager;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.autohost.AutoHost;
import com.magmaguy.resourcepackmanager.commands.DataComplianceRequestCommand;
import com.magmaguy.resourcepackmanager.commands.ReloadCommand;
import com.magmaguy.resourcepackmanager.config.BlueprintFolder;
import com.magmaguy.resourcepackmanager.config.DataConfig;
import com.magmaguy.resourcepackmanager.config.DefaultConfig;
import com.magmaguy.resourcepackmanager.listeners.ResourcePackGeneratedEvent;
import com.magmaguy.resourcepackmanager.mixer.Mix;
import com.magmaguy.resourcepackmanager.playermanager.PlayerManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class ResourcePackManager extends JavaPlugin {

    public static JavaPlugin plugin;

    @Override
    public void onEnable() {
        Bukkit.getLogger().info("\n" +
                "  ___  ___ ___ __  __                             \n" +
                " | _ \\/ __| _ \\  \\/  |__ _ _ _  __ _ __ _ ___ _ _ \n" +
                " |   /\\__ \\  _/ |\\/| / _` | ' \\/ _` / _` / -_) '_|\n" +
                " |_|_\\|___/_| |_|  |_\\__,_|_||_\\__,_\\__, \\___|_|  \n" +
                "                                    |___/         ");
        Bukkit.getLogger().info("ResourcePackManager v." + this.getDescription().getVersion());
        MagmaCore.createInstance(this);

        plugin = this;
        new DefaultConfig();
        new DataConfig();
        BlueprintFolder.initialize();
        Mix.initialize();
        if (DefaultConfig.isAutoHost())
            Bukkit.getPluginManager().registerEvents(new PlayerManager(), this);
        CommandManager commandManager = new CommandManager(this, "resourcepackmanager");
        commandManager.registerCommand(new ReloadCommand());
        commandManager.registerCommand(new DataComplianceRequestCommand());
        Bukkit.getPluginManager().registerEvents(new ResourcePackGeneratedEvent(), this);
        AutoHost.initialize();
    }

    @Override
    public void onDisable() {
        Logger.info("Disabling ResourcePackManager");
        AutoHost.shutdown();
    }
}