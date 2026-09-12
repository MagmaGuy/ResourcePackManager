package com.magmaguy.resourcepackmanager.config.compatibleplugins.premade;

import com.magmaguy.resourcepackmanager.config.compatibleplugins.CompatiblePluginConfigFields;


public class ItemsAdder extends CompatiblePluginConfigFields {
    public ItemsAdder() {
        super("items_adder", true);
        setPluginName("ItemsAdder");
        setLocalPath("ItemsAdder/output/generated.zip");
    }
}