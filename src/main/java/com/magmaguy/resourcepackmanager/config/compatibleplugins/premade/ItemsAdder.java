package com.magmaguy.resourcepackmanager.config.compatibleplugins.premade;

import com.magmaguy.resourcepackmanager.config.compatibleplugins.CompatiblePluginConfigFields;

import java.io.File;

public class ItemsAdder extends CompatiblePluginConfigFields {
    public ItemsAdder() {
        super("items_adder", true);
        setPluginName("ItemsAdder");
        setLocalPath("ItemsAdder" + File.separatorChar + "output" + File.separatorChar + "generated.zip");
        setEncrypts(true);
        setDistributes(true);
    }
}