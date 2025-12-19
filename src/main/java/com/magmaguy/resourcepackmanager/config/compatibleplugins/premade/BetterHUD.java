package com.magmaguy.resourcepackmanager.config.compatibleplugins.premade;

import com.magmaguy.resourcepackmanager.config.compatibleplugins.CompatiblePluginConfigFields;

import java.io.File;

public class BetterHUD extends CompatiblePluginConfigFields {
    public BetterHUD() {
        super("better_hud", true);
        setPluginName("BetterHUD");
        setLocalPath("BetterHUD" + File.separatorChar + "build.zip");
        setEncrypts(false);
        setReloadCommand("betterhud reload");
    }
}
