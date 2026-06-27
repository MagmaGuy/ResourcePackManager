package com.magmaguy.resourcepackmanager.config.compatibleplugins;

import com.magmaguy.magmacore.config.CustomConfig;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

public class CompatiblePluginConfig extends CustomConfig {
    @Getter
    private static Map<String, CompatiblePluginConfigFields> compatiblePlugins = new HashMap<>();

    public CompatiblePluginConfig() {
        super("compatible_plugins", "com.magmaguy.resourcepackmanager.config.compatibleplugins.premade", CompatiblePluginConfigFields.class);
        compatiblePlugins = new HashMap<>();
        for (String key : super.getCustomConfigFieldsHashMap().keySet())
            compatiblePlugins.put(key, (CompatiblePluginConfigFields) super.getCustomConfigFieldsHashMap().get(key));
    }
}
