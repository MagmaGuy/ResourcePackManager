package com.magmaguy.resourcepackmanager;

import org.bukkit.Bukkit;

public class Logger {
    private Logger(){}
    public static void warn(String message){
        Bukkit.getLogger().warning("[ResourcePackManager] " + message);
    }

    public static void info(String message){
        Bukkit.getLogger().info("[ResourcePackManager] " + message);
    }
}
