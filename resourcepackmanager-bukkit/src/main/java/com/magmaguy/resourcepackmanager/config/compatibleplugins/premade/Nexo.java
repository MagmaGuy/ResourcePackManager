package com.magmaguy.resourcepackmanager.config.compatibleplugins.premade;

import com.magmaguy.resourcepackmanager.config.compatibleplugins.CompatiblePluginConfigFields;

import java.io.File;

public class Nexo extends CompatiblePluginConfigFields {
    public Nexo() {
        super("nexo", true);
        setPluginName("Nexo");
        setLocalPath("Nexo" + File.separatorChar + "pack" + File.separatorChar + "pack.zip");
    }
}
