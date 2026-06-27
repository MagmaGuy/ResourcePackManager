package com.magmaguy.resourcepackmanager.config.compatibleplugins.premade;

import com.magmaguy.resourcepackmanager.config.compatibleplugins.CompatiblePluginConfigFields;

import java.io.File;

public class MegaBlockSurvivors extends CompatiblePluginConfigFields {
    public MegaBlockSurvivors() {
        super("megablock_survivors", true);
        setPluginName("MegaBlockSurvivors");
        setLocalPath("MegaBlockSurvivors" + File.separatorChar + "resourcepack");
        setZips(false);
    }
}