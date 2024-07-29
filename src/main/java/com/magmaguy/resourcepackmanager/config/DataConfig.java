package com.magmaguy.resourcepackmanager.config;

import com.magmaguy.magmacore.config.ConfigurationFile;
import com.magmaguy.magmacore.util.Logger;

public class DataConfig extends ConfigurationFile {
    private static DataConfig instance = null;

    public DataConfig() {
        super("data.yml");
        instance = this;
    }

    public static String getRspUUID() {
        String uuid = instance.getFileConfiguration().getString("uuid");
        if (uuid == null) uuid = "";
        return uuid;
    }

    public static void setRspUUID(String rspUUID) {
        instance.getFileConfiguration().set("uuid", rspUUID);
        try {
            instance.getFileConfiguration().save(instance.file);
        } catch (Exception e) {
            Logger.warn("Failed to save uuid!");
            e.printStackTrace();
        }
    }

    @Override
    public void initializeValues() {

    }
}
