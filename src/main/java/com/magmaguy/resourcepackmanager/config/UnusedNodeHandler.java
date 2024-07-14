package com.magmaguy.resourcepackmanager.config;

import com.magmaguy.resourcepackmanager.Logger;
import org.bukkit.Bukkit;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.file.FileConfiguration;

public class UnusedNodeHandler {

    private UnusedNodeHandler() {
    }

    public static Configuration clearNodes(FileConfiguration configuration) {

        for (String actual : configuration.getKeys(false)) {
            boolean keyExists = false;
            for (String defaults : configuration.getDefaults().getKeys(true))
                if (actual.equals(defaults)) {
                    keyExists = true;
                    break;
                }

            if (!keyExists) {
                configuration.set(actual, null);
                Bukkit.getLogger().warning(actual);
                Logger.warn("Deleting unused config values.");
            }
        }
        return configuration;
    }
}
