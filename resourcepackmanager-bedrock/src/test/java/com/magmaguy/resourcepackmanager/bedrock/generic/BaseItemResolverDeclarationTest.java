package com.magmaguy.resourcepackmanager.bedrock.generic;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BaseItemResolverDeclarationTest {
    @TempDir
    Path directory;

    private ItemsDefinition wand() throws Exception {
        Path items = directory.resolve("assets/freeminecraftmodels/items/display/fmm_default_arcane_wand.json");
        Files.createDirectories(items.getParent());
        Files.writeString(items, "{\"model\": {\"type\": \"minecraft:model\", \"model\": \"freeminecraftmodels:display/fmm_default_arcane_wand\"}}");
        return new ItemsDefinition("freeminecraftmodels", "display/fmm_default_arcane_wand", items.toFile(), new JsonObject());
    }

    @Test
    void readsTheProducerDeclaredBaseItems() throws Exception {
        ItemsDefinition wand = wand();
        Path sidecar = directory.resolve("assets/freeminecraftmodels/rspm_item_bases/display/fmm_default_arcane_wand.json");
        Files.createDirectories(sidecar.getParent());
        Files.writeString(sidecar, "{\"base_items\": [\"minecraft:blaze_rod\", \"not a key\"]}");

        assertEquals(List.of("minecraft:blaze_rod"), BaseItemResolver.declaredBaseItems(wand),
                "A wand YAML on BLAZE_ROD must map under blaze_rod, not just the stick the filename suggests");
    }

    @Test
    void modelsWithoutADeclarationKeepTheHeuristic() throws Exception {
        assertEquals(List.of(), BaseItemResolver.declaredBaseItems(wand()));
    }
}
