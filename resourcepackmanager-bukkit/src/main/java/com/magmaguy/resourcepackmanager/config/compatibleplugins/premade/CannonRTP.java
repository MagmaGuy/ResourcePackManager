package com.magmaguy.resourcepackmanager.config.compatibleplugins.premade;

import com.magmaguy.resourcepackmanager.config.compatibleplugins.CompatiblePluginConfigFields;

import java.io.File;

public class CannonRTP extends CompatiblePluginConfigFields {
    public CannonRTP() {
        super("cannon_rtp", true);
        setPluginName("CannonRTP");
        setLocalPath("CannonRTP" + File.separatorChar + "resource_pack");
        setCluster(true);
        setZips(false);
        setReloadCommand("wc reload");
    }
}
