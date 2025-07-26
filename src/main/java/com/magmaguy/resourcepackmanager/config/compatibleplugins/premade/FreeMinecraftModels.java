package com.magmaguy.resourcepackmanager.config.compatibleplugins.premade;

import com.magmaguy.resourcepackmanager.config.compatibleplugins.CompatiblePluginConfigFields;

import java.io.File;

public class FreeMinecraftModels extends CompatiblePluginConfigFields {
    public FreeMinecraftModels() {
        super("free_minecraft_models", true);
        setPluginName("FreeMinecraftModels");
        setLocalPath("FreeMinecraftModels" + File.separatorChar + "output" + File.separatorChar + "FreeMinecraftModels.zip");
        setReloadCommand("freeminecraftmodels reload");
    }
}