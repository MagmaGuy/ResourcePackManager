package com.magmaguy.resourcepackmanager.config.compatibleplugins.premade;

import com.magmaguy.resourcepackmanager.config.compatibleplugins.CompatiblePluginConfigFields;

import java.io.File;

public class ResourcePackManager extends CompatiblePluginConfigFields {
    public ResourcePackManager() {
        super("resource_pack_manager", true);
        setPluginName("ResourcePackManager");
        setLocalPath("ResourcePackManager" + File.separatorChar + "blueprint" + File.separatorChar + "blueprint.zip");
        setReloadCommand("rspm reload");
    }
}