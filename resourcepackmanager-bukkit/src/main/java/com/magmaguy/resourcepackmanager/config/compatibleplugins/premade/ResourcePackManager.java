package com.magmaguy.resourcepackmanager.config.compatibleplugins.premade;

import com.magmaguy.resourcepackmanager.config.compatibleplugins.CompatiblePluginConfigFields;


public class ResourcePackManager extends CompatiblePluginConfigFields {
    public ResourcePackManager() {
        super("resource_pack_manager", true);
        setPluginName("ResourcePackManager");
        setLocalPath("ResourcePackManager/blueprint/blueprint.zip");
        setAdditionalLocalPath("ResourcePackManager/resource_pack");
        setReloadCommand("rspm reload");
    }
}