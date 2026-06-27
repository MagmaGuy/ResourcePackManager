package com.magmaguy.resourcepackmanager.config;

import com.magmaguy.magmacore.config.ConfigurationFile;
import com.magmaguy.magmacore.util.Logger;

import java.util.UUID;

public class DataConfig extends ConfigurationFile {
    private static DataConfig instance = null;

    public DataConfig() {
        super("data.yml");
        instance = this;
    }

    public static String getRspUUID() {
        String uuid = instance.getFileConfiguration().getString("uuid");

        // If UUID exists but is invalid, clear it and return null
        if (uuid != null && !uuid.isEmpty() && !isValidUUID(uuid)) {
            Logger.warn("Invalid UUID found in config file: " + uuid + ". Deleting invalid UUID from file.");
            instance.clearInvalidUUID();
            return null;
        }

        // Return null for empty/null UUIDs instead of empty string
        return (uuid == null || uuid.isEmpty()) ? "" : uuid;
    }

    public static void setRspUUID(String rspUUID) {
        // Validate UUID if not null/empty
        if (rspUUID != null && !rspUUID.isEmpty() && !isValidUUID(rspUUID)) {
            throw new IllegalArgumentException("Invalid UUID format: " + rspUUID +
                    ". UUID must be in format: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx");
        }

        // Store null as null, not empty string
        instance.getFileConfiguration().set("uuid", rspUUID);
        try {
            instance.getFileConfiguration().save(instance.file);
            if (rspUUID != null && !rspUUID.isEmpty()) {
                Logger.info("Successfully saved UUID: " + rspUUID);
            } else {
                Logger.info("Successfully cleared UUID from config.");
            }
        } catch (Exception e) {
            Logger.warn("Failed to save uuid!");
            e.printStackTrace();
        }
    }

    /**
     * Clears invalid UUID from config file
     */
    private void clearInvalidUUID() {
        getFileConfiguration().set("uuid", null);
        try {
            getFileConfiguration().save(file);
            Logger.info("Invalid UUID cleared from config file.");
        } catch (Exception e) {
            Logger.warn("Failed to clear invalid UUID from config!");
            e.printStackTrace();
        }
    }

    /**
     * Validates if a string is a proper UUID format
     * @param uuidString The string to validate
     * @return true if valid UUID format, false otherwise
     */
    private static boolean isValidUUID(String uuidString) {
        if (uuidString == null || uuidString.trim().isEmpty()) {
            return false;
        }

        try {
            // Use Java's built-in UUID validation
            UUID.fromString(uuidString.trim());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public void initializeValues() {
        // Validate existing UUID on initialization and clear if invalid
        String existingUUID = getFileConfiguration().getString("uuid");
        if (existingUUID != null && !existingUUID.isEmpty() && !isValidUUID(existingUUID)) {
            Logger.warn("Invalid UUID found in config file during initialization: " + existingUUID);
            Logger.warn("Deleting invalid UUID from file. A new one will be generated on next server connection.");
            clearInvalidUUID();
        }
    }
}