package com.magmaguy.resourcepackmanager.itemsadder;

import com.magmaguy.magmacore.config.ConfigurationFile;
import com.magmaguy.magmacore.util.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Stores which players have dismissed the ItemsAdder configuration warning.
 */
public class ItemsAdderDismissedConfig extends ConfigurationFile {

    private static ItemsAdderDismissedConfig instance = null;

    public ItemsAdderDismissedConfig() {
        super("itemsadder_dismissed.yml");
        instance = this;
    }

    /**
     * Check if a player has dismissed the warning.
     * @param playerUUID the player's UUID
     * @return true if the player has dismissed the warning
     */
    public static boolean hasDismissed(UUID playerUUID) {
        if (instance == null) return false;

        List<String> dismissed = instance.getFileConfiguration().getStringList("dismissed");
        return dismissed.contains(playerUUID.toString());
    }

    /**
     * Record that a player has dismissed the warning.
     * @param playerUUID the player's UUID
     */
    public static void setDismissed(UUID playerUUID) {
        if (instance == null) return;

        List<String> dismissedList = new ArrayList<>(instance.getFileConfiguration().getStringList("dismissed"));

        if (!dismissedList.contains(playerUUID.toString())) {
            dismissedList.add(playerUUID.toString());
        }

        instance.getFileConfiguration().set("dismissed", dismissedList);
        try {
            instance.getFileConfiguration().save(instance.file);
        } catch (Exception e) {
            Logger.warn("Failed to save ItemsAdder dismissed config!");
            e.printStackTrace();
        }
    }

    @Override
    public void initializeValues() {
        // Ensure the dismissed list exists
        if (!getFileConfiguration().contains("dismissed")) {
            getFileConfiguration().set("dismissed", new ArrayList<String>());
            try {
                getFileConfiguration().save(file);
            } catch (Exception e) {
                Logger.warn("Failed to initialize ItemsAdder dismissed config!");
            }
        }
    }
}
