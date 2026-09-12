package com.magmaguy.resourcepackmanager.config.compatibleplugins.premade;

import com.magmaguy.resourcepackmanager.config.compatibleplugins.CompatiblePluginConfigFields;


public class EliteMobs extends CompatiblePluginConfigFields {
    public EliteMobs() {
        super("elitemobs", true);
        setPluginName("EliteMobs");
        setLocalPath("EliteMobs/resource_pack");
        setReloadCommand("elitemobs reload");
        setCluster(true);
        setZips(false);
    }
}