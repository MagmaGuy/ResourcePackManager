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
     * Set whether a player has dismissed the warning.
     * @param playerUUID the player's UUID
     * @param dismissed true to dismiss, false to un-dismiss
     */
    public static void setDismissed(UUID playerUUID, boolean dismissed) {
        if (instance == null) return;

        List<String> dismissedList = new ArrayList<>(instance.getFileConfiguration().getStringList("dismissed"));

        if (dismissed && !dismissedList.contains(playerUUID.toString())) {
            dismissedList.add(playerUUID.toString());
        } else if (!dismissed) {
            dismissedList.remove(playerUUID.toString());
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
