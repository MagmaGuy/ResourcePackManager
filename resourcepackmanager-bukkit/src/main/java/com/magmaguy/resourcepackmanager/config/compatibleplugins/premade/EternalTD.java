package com.magmaguy.resourcepackmanager.config.compatibleplugins.premade;

import com.magmaguy.resourcepackmanager.config.compatibleplugins.CompatiblePluginConfigFields;

import java.io.File;

public class EternalTD extends CompatiblePluginConfigFields {
    public EternalTD() {
        super("eternal_td", true);
        setPluginName("EternalTD");
        setLocalPath("EternalTD" + File.separatorChar + "resource_pack");
        setCluster(true);
        setZips(false);
        setReloadCommand("eternaltd reload");
    }
}
