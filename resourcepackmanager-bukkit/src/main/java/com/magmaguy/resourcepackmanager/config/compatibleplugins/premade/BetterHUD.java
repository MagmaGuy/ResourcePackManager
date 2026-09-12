package com.magmaguy.resourcepackmanager.config.compatibleplugins.premade;

import com.magmaguy.resourcepackmanager.config.compatibleplugins.CompatiblePluginConfigFields;


public class BetterHUD extends CompatiblePluginConfigFields {
    public BetterHUD() {
        super("better_hud", true);
        setPluginName("BetterHUD");
        setLocalPath("BetterHUD/build.zip");
        setReloadCommand("betterhud reload");
    }
}
