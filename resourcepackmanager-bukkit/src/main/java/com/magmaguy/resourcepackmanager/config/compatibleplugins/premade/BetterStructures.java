package com.magmaguy.resourcepackmanager.config.compatibleplugins.premade;

import com.magmaguy.resourcepackmanager.config.compatibleplugins.CompatiblePluginConfigFields;


public class BetterStructures extends CompatiblePluginConfigFields {
    public BetterStructures() {
        super("better_structures", true);
        setPluginName("BetterStructures");
        setLocalPath("BetterStructures/resource_pack");
        setCluster(true);
        setZips(false);
        setReloadCommand("betterstructures reload");
    }
}
