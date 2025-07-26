package com.magmaguy.resourcepackmanager.config.compatibleplugins.premade;

import com.magmaguy.resourcepackmanager.config.compatibleplugins.CompatiblePluginConfigFields;

public class VaneCore extends CompatiblePluginConfigFields {
    public VaneCore() {
        super("vane_core", true);
        setPluginName("vane-core");
        setLocalPath("vane-resource-pack.zip");
        setDistributes(true);
    }
}