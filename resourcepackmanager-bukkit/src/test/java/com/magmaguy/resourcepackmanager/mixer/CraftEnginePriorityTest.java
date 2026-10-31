package com.magmaguy.resourcepackmanager.mixer;

import com.magmaguy.resourcepackmanager.config.DefaultConfig;
import com.magmaguy.resourcepackmanager.config.compatibleplugins.premade.CraftEngine;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The built-in CraftEngine integration (RSPM#20) and where its pack lands in the merge. */
final class CraftEnginePriorityTest {

    @Test
    void theBuiltInEntryReadsCraftEnginesGeneratedZip() {
        CraftEngine entry = new CraftEngine();
        entry.setFileConfiguration(new YamlConfiguration());
        entry.processConfigFields();

        assertEquals("craft_engine.yml", entry.getFilename());
        assertTrue(entry.isEnabled());
        assertEquals("CraftEngine", entry.getPluginName());
        assertEquals("CraftEngine/generated/resource_pack.zip", entry.getLocalPath());
        assertTrue(entry.isZips(), "CraftEngine writes a finished zip, which RSPM copies as is");
        assertNull(entry.getUrl());
        assertEquals("CraftEngine/generated/resource_pack.zip",
                entry.getFileConfiguration().getDefaults().getString("localPath"), "Written to a new craft_engine.yml");
    }

    @Test
    void anExistingCraftEngineYmlKeepsItsSettings() {
        YamlConfiguration existing = new YamlConfiguration();
        existing.set("isEnabled", true);
        existing.set("pluginName", "CraftEngine");
        existing.set("localPath", "CraftEngine/custom/pack.zip");
        CraftEngine entry = new CraftEngine();
        entry.setFileConfiguration(existing);
        entry.processConfigFields();

        assertEquals("CraftEngine/custom/pack.zip", entry.getLocalPath(),
                "A craft_engine.yml written by hand before this entry existed stays in charge");
    }

    @Test
    void craftEngineMergesRightAfterOraxen() {
        List<String> order = DefaultConfig.DEFAULT_PRIORITY_ORDER;
        int craftEngine = order.indexOf("CraftEngine");

        assertEquals(order.indexOf("Oraxen") + 1, craftEngine);
        assertEquals(craftEngine, Mix.configuredPriority(new File("CraftEngine_resource_pack.zip"), order));

        List<String> sorted = List.of("BetterHUD", "CraftEngine", "ItemsAdder", "Oraxen").stream()
                .map(plugin -> new File(plugin + "_resource_pack.zip"))
                .sorted(Comparator.comparingInt(file -> Mix.configuredPriority(file, order)))
                .map(file -> file.getName().replace("_resource_pack.zip", ""))
                .toList();
        assertEquals(List.of("ItemsAdder", "Oraxen", "CraftEngine", "BetterHUD"), sorted);
    }

    @Test
    void anOlderPriorityListWithoutCraftEngineLeavesItLast() {
        List<String> olderDefault = DefaultConfig.DEFAULT_PRIORITY_ORDER.stream()
                .filter(plugin -> !plugin.equals("CraftEngine")).toList();

        assertEquals(-1, Mix.configuredPriority(new File("CraftEngine_resource_pack.zip"), olderDefault),
                "Unlisted packs get the lowest priority; existing configs need CraftEngine added by hand");
    }
}
