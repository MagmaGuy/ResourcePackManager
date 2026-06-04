package com.magmaguy.resourcepackmanager.bedrock.generic;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericJavaScannerTest {

    @TempDir
    Path tempDir;

    @Test
    void legacyCustomModelDataOverrideProducesDefinitionWithExplicitBaseItem() throws Exception {
        Path vanillaItemModel = tempDir.resolve("assets/minecraft/models/item/paper.json");
        Files.createDirectories(vanillaItemModel.getParent());
        Files.writeString(vanillaItemModel, """
                {
                  "parent": "minecraft:item/generated",
                  "textures": { "layer0": "minecraft:item/paper" },
                  "overrides": [
                    {
                      "predicate": { "custom_model_data": 101 },
                      "model": "abbys_goodies:item/ia_auto/inori_fish"
                    }
                  ]
                }
                """);

        List<ItemsDefinition> definitions = GenericJavaScanner.scan(tempDir.toFile());

        assertEquals(1, definitions.size());
        ItemsDefinition def = definitions.get(0);
        assertEquals("abbys_goodies", def.namespace());
        assertEquals("item/ia_auto/inori_fish", def.itemsRelPath());
        assertEquals("abbys_goodies:item/ia_auto/inori_fish", def.itemIdentifier());
        assertEquals(List.of("minecraft:paper"), def.explicitBaseItems());

        List<ResolvedLeaf> leaves = ItemModelTreeWalker.walk(def, null);
        assertEquals(1, leaves.size());
        assertEquals("abbys_goodies:item/ia_auto/inori_fish", leaves.get(0).modelRef());
        assertEquals(1, leaves.get(0).predicates().size());

        JsonObject predicate = leaves.get(0).predicates().get(0).toGeyserJson();
        assertEquals("range_dispatch", predicate.get("type").getAsString());
        assertEquals("custom_model_data", predicate.get("property").getAsString());
        assertEquals(101.0, predicate.get("threshold").getAsDouble());
        assertTrue(def.hasExplicitBaseItems());
    }
}
