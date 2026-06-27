package com.magmaguy.resourcepackmanager.config.compatibleplugins.premade;

import com.magmaguy.resourcepackmanager.config.compatibleplugins.CompatiblePluginConfigFields;

import java.io.File;

public class EliteMobs extends CompatiblePluginConfigFields {
    public EliteMobs() {
        super("elitemobs", true);
        setPluginName("EliteMobs");
        setLocalPath("EliteMobs" + File.separatorChar + "resource_pack");
        setReloadCommand("elitemobs reload");
        setCluster(true);
        setZips(false);
    }
}