package com.magmaguy.resourcepackmanager.bedrock.generic;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaseItemResolverDeclarationTest {
    @TempDir
    Path directory;

    private ItemsDefinition wand() throws Exception {
        return definition("freeminecraftmodels", "display/fmm_default_arcane_wand");
    }

    private ItemsDefinition definition(String namespace, String path) throws Exception {
        Path items = directory.resolve("assets/" + namespace + "/items/" + path + ".json");
        Files.createDirectories(items.getParent());
        Files.writeString(items, "{\"model\": {\"type\": \"minecraft:model\", \"model\": \"" + namespace + ":" + path + "\"}}");
        return new ItemsDefinition(namespace, path, items.toFile(), new JsonObject());
    }

    private void declareInThePack(String namespace, String path, String base) throws Exception {
        Path sidecar = directory.resolve("assets/" + namespace + "/rspm_item_bases/" + path + ".json");
        Files.createDirectories(sidecar.getParent());
        Files.writeString(sidecar, "{\"base_items\": [\"" + base + "\", \"not a key\"]}");
    }

    private List<String> resolve(ItemsDefinition definition, Map<String, List<String>> declared, List<String> carriers) {
        return BaseItemResolver.resolve(definition, new AssetResolver(directory.toFile()), declared, carriers);
    }

    @Test
    void readsTheProducerDeclaredBaseItems() throws Exception {
        ItemsDefinition wand = wand();
        declareInThePack("freeminecraftmodels", "display/fmm_default_arcane_wand", "minecraft:blaze_rod");

        assertEquals(List.of("minecraft:blaze_rod"), BaseItemResolver.declaredBaseItems(wand),
                "A wand YAML on BLAZE_ROD must map under blaze_rod, not just the stick the filename suggests");
    }

    @Test
    void modelsWithoutADeclarationKeepTheHeuristic() throws Exception {
        assertEquals(List.of(), BaseItemResolver.declaredBaseItems(wand()));
        assertEquals(FilenameHeuristic.genericFallback(), resolve(definition("pack", "tinder"), Map.of(), List.of()));
        assertEquals(6, resolve(definition("pack", "iron_axe"), Map.of("pack:other", List.of("minecraft:paper")),
                List.of()).size(), "a declaration for another model changes nothing");
    }

    @Test
    void aDeclaredModelMapsOnlyUnderItsDeclaredBases() throws Exception {
        assertTrue(BaseItemResolver.DECLARED_BASES_REPLACE_GUESSES, "this test describes the replace rule");

        assertEquals(List.of("minecraft:flint_and_steel"), resolve(definition("pack", "tinder"),
                Map.of("pack:tinder", List.of("minecraft:flint_and_steel")), List.of()));
        // Named like an axe, made on a copper axe: none of the six guessed axes.
        assertEquals(List.of("minecraft:copper_axe", "minecraft:stone_axe"), resolve(definition("pack", "war_axe"),
                Map.of("pack:war_axe", List.of("minecraft:copper_axe", "minecraft:stone_axe", "not a key")), List.of()));

        declareInThePack("pack", "wand", "minecraft:blaze_rod");
        assertEquals(List.of("minecraft:blaze_rod"), resolve(definition("pack", "wand"), Map.of(), List.of()),
                "an rspm_item_bases file in the pack is a declaration too");
    }

    @Test
    void theBaseItemsThePackPutsAModelOnStayNextToDeclaredOnes() throws Exception {
        assertEquals(List.of("minecraft:leather_horse_armor", "minecraft:bone"),
                resolve(definition("pack", "bone_head"), Map.of("pack:bone_head", List.of("minecraft:bone")),
                        List.of("minecraft:leather_horse_armor")));
    }

    @Test
    void aDeclaredFreeMinecraftModelsItemKeepsItsOwnTwoCarriers() throws Exception {
        declareInThePack("freeminecraftmodels", "display/fmm_default_arcane_wand", "minecraft:blaze_rod");

        // /fmm craftify hands the model out on paper, and bones show on leather horse armor.
        assertEquals(List.of("minecraft:paper", "minecraft:leather_horse_armor", "minecraft:blaze_rod"),
                resolve(wand(), Map.of(), List.of()));
    }
}
