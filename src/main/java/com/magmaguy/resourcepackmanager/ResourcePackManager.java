package com.magmaguy.resourcepackmanager;

import com.magmaguy.resourcepackmanager.autohost.AutoHost;
import com.magmaguy.resourcepackmanager.commands.CommandManager;
import com.magmaguy.resourcepackmanager.config.DefaultConfig;
import com.magmaguy.resourcepackmanager.mixer.Mix;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public class ResourcePackManager extends JavaPlugin {

    public static Plugin plugin;

    @Override
    public void onEnable() {
        Bukkit.getLogger().info("\n" +
                "  ___  ___ ___ __  __                             \n" +
                " | _ \\/ __| _ \\  \\/  |__ _ _ _  __ _ __ _ ___ _ _ \n" +
                " |   /\\__ \\  _/ |\\/| / _` | ' \\/ _` / _` / -_) '_|\n" +
                " |_|_\\|___/_| |_|  |_\\__,_|_||_\\__,_\\__, \\___|_|  \n" +
                "                                    |___/         ");
        Logger.info("Enabling ResourcePackManager v." + this.getDescription().getVersion());
        plugin = this;
        DefaultConfig.initializeConfig();
        Mix.initialize();
        AutoHost.initialize();
        new CommandManager(this);
    }

    @Override
    public void onDisable() {
        Logger.info("Disabling ResourcePackManager");
        AutoHost.shutdown();
    }
}