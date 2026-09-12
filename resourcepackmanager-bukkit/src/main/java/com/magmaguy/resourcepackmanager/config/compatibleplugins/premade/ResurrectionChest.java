package com.magmaguy.resourcepackmanager.config.compatibleplugins.premade;

import com.magmaguy.resourcepackmanager.config.compatibleplugins.CompatiblePluginConfigFields;


public class ResurrectionChest extends CompatiblePluginConfigFields {
    public ResurrectionChest() {
        super("resurrection_chest", true);
        setPluginName("ResurrectionChest");
        setLocalPath("ResurrectionChest/resource_pack");
        setCluster(true);
        setZips(false);
        setReloadCommand("resurrectionchest reload");
    }
}
