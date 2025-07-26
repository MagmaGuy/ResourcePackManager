package com.magmaguy.resourcepackmanager.config.compatibleplugins.premade;

import com.magmaguy.resourcepackmanager.config.compatibleplugins.CompatiblePluginConfigFields;

import java.io.File;

public class BackpackPlus extends CompatiblePluginConfigFields {
    public BackpackPlus() {
        super("backpack_plus", true);
        setPluginName("BackpackPlus");
        setLocalPath("BackpackPlus" + File.separatorChar + "pack" + File.separatorChar + "resourcepack.zip");
    }
}
