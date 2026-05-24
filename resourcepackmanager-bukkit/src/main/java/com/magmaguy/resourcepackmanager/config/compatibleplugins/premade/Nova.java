package com.magmaguy.resourcepackmanager.config.compatibleplugins.premade;

import com.magmaguy.resourcepackmanager.config.compatibleplugins.CompatiblePluginConfigFields;

import java.io.File;

public class Nova extends CompatiblePluginConfigFields {
    public Nova() {
        super("nova", true);
        setPluginName("Nova");
        setLocalPath("Nova" + File.separatorChar + "resource_pack" + File.separatorChar + "ResourcePack.zip");
    }
}