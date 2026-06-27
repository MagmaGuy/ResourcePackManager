package com.magmaguy.resourcepackmanager.config.compatibleplugins.premade;

import com.magmaguy.resourcepackmanager.config.compatibleplugins.CompatiblePluginConfigFields;

import java.io.File;

public class ValhallaMMO extends CompatiblePluginConfigFields {
    public ValhallaMMO() {
        super("valhalla_mmo", true);
        setPluginName("ValhallaMMO");
        setLocalPath("ValhallaMMO" + File.separatorChar + "resourcepack");
        setZips(false);
    }
}