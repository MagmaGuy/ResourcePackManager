package com.magmaguy.resourcepackmanager.config.compatibleplugins.premade;

import com.magmaguy.resourcepackmanager.config.compatibleplugins.CompatiblePluginConfigFields;

import java.io.File;

public class BetterHUD extends CompatiblePluginConfigFields {
    public BetterHUD() {
        super("better_hud", true);
        setPluginName("BetterHUD");
        setLocalPath("BackpackPlus" + File.separatorChar + "pack" + File.separatorChar + "resourcepack.zip");
        setEncrypts(true);
        setReloadCommand("betterhud reload");
    }
}
