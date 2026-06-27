package com.magmaguy.resourcepackmanager.config.compatibleplugins.premade;

import com.magmaguy.resourcepackmanager.config.compatibleplugins.CompatiblePluginConfigFields;

import java.io.File;

public class InfiniteVehicles extends CompatiblePluginConfigFields {
    public InfiniteVehicles() {
        super("infinite_vehicles", true);
        setPluginName("InfiniteVehicles");
        setLocalPath("InfiniteVehicles" + File.separatorChar + "InfiniteModelPack.zip");
    }
}