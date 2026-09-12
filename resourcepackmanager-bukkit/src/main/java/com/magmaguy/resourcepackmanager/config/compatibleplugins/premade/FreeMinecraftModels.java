package com.magmaguy.resourcepackmanager.config.compatibleplugins.premade;

import com.magmaguy.resourcepackmanager.config.compatibleplugins.CompatiblePluginConfigFields;


public class FreeMinecraftModels extends CompatiblePluginConfigFields {
    public FreeMinecraftModels() {
        super("free_minecraft_models", true);
        setPluginName("FreeMinecraftModels");
        setLocalPath("FreeMinecraftModels/output/FreeMinecraftModels.zip");
        setAdditionalLocalPath("FreeMinecraftModels/resource_pack");
        setReloadCommand("freeminecraftmodels reload");
    }
}