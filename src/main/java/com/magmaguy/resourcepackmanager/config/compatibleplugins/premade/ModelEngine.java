package com.magmaguy.resourcepackmanager.config.compatibleplugins.premade;

import com.magmaguy.resourcepackmanager.config.compatibleplugins.CompatiblePluginConfigFields;

import java.io.File;

public class ModelEngine extends CompatiblePluginConfigFields {
    public ModelEngine() {
        super("model_engine", true);
        setPluginName("ModelEngine");
        setLocalPath("ModelEngine" + File.separatorChar + "resource pack.zip");
        setReloadCommand("meg reload");
    }
}