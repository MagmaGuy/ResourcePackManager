package com.magmaguy.resourcepackmanager.config.compatibleplugins.premade;

import com.magmaguy.resourcepackmanager.config.compatibleplugins.CompatiblePluginConfigFields;


/**
 * CraftEngine builds its pack in memory and only writes this zip when its pack workflow
 * runs (/ce reload all), so on a fresh install the file appears after RSPM has started.
 */
public class CraftEngine extends CompatiblePluginConfigFields {
    public CraftEngine() {
        super("craft_engine", true);
        setPluginName("CraftEngine");
        setLocalPath("CraftEngine/generated/resource_pack.zip");
        setReloadCommand("ce reload all");
    }
}
