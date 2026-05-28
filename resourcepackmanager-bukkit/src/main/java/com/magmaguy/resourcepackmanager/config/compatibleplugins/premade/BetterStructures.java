package com.magmaguy.resourcepackmanager.config.compatibleplugins.premade;

import com.magmaguy.resourcepackmanager.config.compatibleplugins.CompatiblePluginConfigFields;

import java.io.File;

public class BetterStructures extends CompatiblePluginConfigFields {
    public BetterStructures() {
        super("better_structures", true);
        setPluginName("BetterStructures");
        setLocalPath("BetterStructures" + File.separatorChar + "resource_pack");
        setCluster(true);
        setZips(false);
        setReloadCommand("betterstructures reload");
    }
}
