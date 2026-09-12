package com.magmaguy.resourcepackmanager.config.compatibleplugins.premade;

import com.magmaguy.resourcepackmanager.config.compatibleplugins.CompatiblePluginConfigFields;


public class EternalTD extends CompatiblePluginConfigFields {
    public EternalTD() {
        super("eternal_td", true);
        setPluginName("EternalTD");
        setLocalPath("EternalTD/resource_pack");
        setCluster(true);
        setZips(false);
        setReloadCommand("eternaltd reload");
    }
}
