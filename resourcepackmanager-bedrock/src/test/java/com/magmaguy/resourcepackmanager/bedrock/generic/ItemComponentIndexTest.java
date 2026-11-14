package com.magmaguy.resourcepackmanager.bedrock.generic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The custom items a Geyser definition stands for, picked the way Geyser picks a definition. */
final class ItemComponentIndexTest {

    private static ItemComponentSnapshot item(String id, String base, String patch) {
        return new ItemComponentSnapshot("Test", id, base, JsonParser.parseString(patch).getAsJsonObject(), new JsonObject());
    }

    private static ResolvedLeaf leaf(double customModelData) {
        return new ResolvedLeaf("minecraft:item/paper", List.of(new PredicateRecord.RangeDispatch(
                "custom_model_data", customModelData, 1.0, Map.of("index", new JsonPrimitive(0)))));
    }

    private static List<String> ids(List<ItemComponentSnapshot> stacks) {
        return stacks.stream().map(ItemComponentSnapshot::itemId).toList();
    }

    @Test
    void itemModelDefinitionsMatchStacksWithThatModelOnTheSameBase() {
        ItemComponentIndex index = new ItemComponentIndex(List.of(
                item("default:topaz", "minecraft:nether_brick", "{\"minecraft:item_model\":\"default:topaz\"}"),
                item("default:topaz_paper", "minecraft:paper", "{\"minecraft:item_model\":\"default:topaz\"}"),
                item("default:ruby", "minecraft:nether_brick", "{\"minecraft:item_model\":\"default:ruby\"}")));
        ItemsDefinition topaz = new ItemsDefinition("default", "topaz", null, new JsonObject());
        ResolvedLeaf model = new ResolvedLeaf("default:item/topaz", List.of());

        assertEquals(List.of("default:topaz"), ids(index.matching(topaz, model, List.of(model), "minecraft:nether_brick")));
        assertEquals(List.of("default:topaz_paper"), ids(index.matching(topaz, model, List.of(model), "minecraft:paper")));
        assertEquals(List.of(), index.matching(topaz, model, List.of(model), "minecraft:stick"));
    }

    @Test
    void legacyDefinitionsMatchTheirExactCustomModelData() {
        ItemComponentIndex index = new ItemComponentIndex(List.of(
                item("oraxen:ruby", "minecraft:paper", "{\"minecraft:custom_model_data\":{\"floats\":[1001]}}"),
                item("oraxen:onyx", "minecraft:paper", "{\"minecraft:custom_model_data\":{\"floats\":[1002]}}")));
        ItemsDefinition legacy = ItemsDefinition.legacyCustomModelData("minecraft", "paper", null, new JsonObject(),
                "minecraft:paper", BigDecimal.valueOf(1001));
        ResolvedLeaf model = new ResolvedLeaf("oraxen:item/ruby", List.of());

        assertEquals(List.of("oraxen:ruby"), ids(index.matching(legacy, model, List.of(model), "minecraft:paper")));
    }

    @Test
    void vanillaItemBranchesMatchTheValuesTheirThresholdSelects() {
        ItemComponentIndex index = new ItemComponentIndex(List.of(
                item("below", "minecraft:paper", "{\"minecraft:custom_model_data\":{\"floats\":[500]}}"),
                item("first", "minecraft:paper", "{\"minecraft:custom_model_data\":{\"floats\":[1500]}}"),
                item("second", "minecraft:paper", "{\"minecraft:custom_model_data\":{\"floats\":[2000]}}"),
                item("other_base", "minecraft:stick", "{\"minecraft:custom_model_data\":{\"floats\":[1500]}}")));
        ItemsDefinition paper = new ItemsDefinition("minecraft", "paper", null, new JsonObject());
        List<ResolvedLeaf> leaves = List.of(leaf(2000), leaf(1000));

        assertEquals(List.of("first"), ids(index.matching(paper, leaves.get(1), leaves, "minecraft:paper")));
        assertEquals(List.of("second"), ids(index.matching(paper, leaves.get(0), leaves, "minecraft:paper")));
    }

    @Test
    void onlyAPlainCustomModelDataBranchHasAThreshold() {
        assertEquals(1000.0, ItemComponentIndex.customModelDataThreshold(leaf(1000)));
        assertNull(ItemComponentIndex.customModelDataThreshold(new ResolvedLeaf("m", List.of(
                new PredicateRecord.RangeDispatch("custom_model_data", 1, 1.0, Map.of("index", new JsonPrimitive(1)))))));
        assertNull(ItemComponentIndex.customModelDataThreshold(new ResolvedLeaf("m", List.of(
                new PredicateRecord.RangeDispatch("damage", 1, 1.0, Map.of())))));
        assertTrue(ItemComponentIndex.EMPTY.isEmpty());
    }

    @Test
    void theWalkerReadsTheDyeTintDefault() {
        assertEquals(0xA06540, ItemModelTreeWalker.dyeTintDefault(JsonParser.parseString(
                "{\"type\":\"minecraft:model\",\"tints\":[{\"type\":\"minecraft:dye\",\"default\":-6265536}]}").getAsJsonObject()));
        assertEquals(0xFF0000, ItemModelTreeWalker.dyeTintDefault(JsonParser.parseString(
                "{\"tints\":[{\"type\":\"dye\",\"default\":[1.0,0.0,0.0]}]}").getAsJsonObject()));
        assertNull(ItemModelTreeWalker.dyeTintDefault(JsonParser.parseString(
                "{\"tints\":[{\"type\":\"minecraft:constant\",\"value\":-1}]}").getAsJsonObject()));
        assertNull(ItemModelTreeWalker.dyeTintDefault(new JsonObject()));
    }
}
