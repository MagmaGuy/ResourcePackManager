package com.magmaguy.resourcepackmanager.config.compatibleplugins.premade;

import com.magmaguy.resourcepackmanager.config.compatibleplugins.CompatiblePluginConfigFields;

import java.io.File;

public class ResurrectionChest extends CompatiblePluginConfigFields {
    public ResurrectionChest() {
        super("resurrection_chest", true);
        setPluginName("ResurrectionChest");
        setLocalPath("ResurrectionChest" + File.separatorChar + "resource_pack");
        setCluster(true);
        setZips(false);
        setReloadCommand("resurrectionchest reload");
    }
}
