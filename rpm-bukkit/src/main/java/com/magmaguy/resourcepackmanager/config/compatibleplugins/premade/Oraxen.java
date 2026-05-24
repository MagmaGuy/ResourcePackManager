package com.magmaguy.resourcepackmanager.config.compatibleplugins.premade;

import com.magmaguy.resourcepackmanager.config.compatibleplugins.CompatiblePluginConfigFields;

import java.io.File;

public class Oraxen extends CompatiblePluginConfigFields {
    public Oraxen() {
        super("oraxen", true);
        setPluginName("Oraxen");
        setLocalPath("Oraxen" + File.separatorChar + "pack" + File.separatorChar + "pack.zip");
    }
}