package com.magmaguy.resourcepackmanager.config.compatibleplugins.premade;

import com.magmaguy.resourcepackmanager.config.compatibleplugins.CompatiblePluginConfigFields;


public class CannonRTP extends CompatiblePluginConfigFields {
    public CannonRTP() {
        super("cannon_rtp", true);
        setPluginName("CannonRTP");
        setLocalPath("CannonRTP/resource_pack");
        setCluster(true);
        setZips(false);
        setReloadCommand("wc reload");
    }
}
