package com.magmaguy.resourcepackmanager.bedrock.generic;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemModelTreeWalkerConditionTest {

    /** A copper tool that changes its look as it wears down, the way datapacks write it. */
    private static final String WEARING_TOOL = """
            {"model": {
              "type": "minecraft:condition",
              "property": "minecraft:component",
              "predicate": "minecraft:damage",
              "value": {"damage": {"min": 0, "max": 87}},
              "on_true": {"type": "minecraft:model", "model": "pack:item/tool"},
              "on_false": {
                "type": "minecraft:condition",
                "property": "minecraft:component",
                "predicate": "minecraft:damage",
                "value": {"damage": {"min": 88, "max": 999}},
                "on_true": {"type": "minecraft:model", "model": "pack:item/tool_oxidized"},
                "on_false": {"type": "minecraft:model", "model": "pack:item/tool"}
              }
            }}""";

    private static List<ResolvedLeaf> walk(String json) {
        return ItemModelTreeWalker.walk(new ItemsDefinition("pack", "tool", new File("tool.json"),
                JsonParser.parseString(json).getAsJsonObject()));
    }

    @Test
    void anUnsupportedConditionKeepsItsElseBranch() {
        List<ResolvedLeaf> leaves = walk(WEARING_TOOL);

        assertEquals(1, leaves.size(), "leaves: " + leaves);
        assertEquals("pack:item/tool", leaves.get(0).modelRef());
        assertTrue(leaves.get(0).predicates().isEmpty(),
                "Geyser cannot test the condition, so the else-look maps without a predicate");
    }

    @Test
    void theElseBranchKeepsThePredicatesAboveIt() {
        List<ResolvedLeaf> leaves = walk("""
                {"model": {
                  "type": "minecraft:select",
                  "property": "minecraft:custom_model_data",
                  "cases": [{"when": "open", "model": {
                    "type": "minecraft:condition",
                    "property": "minecraft:bundle/has_selected_item",
                    "on_true": {"type": "minecraft:model", "model": "pack:item/bundle_open"},
                    "on_false": {"type": "minecraft:model", "model": "pack:item/bundle"}
                  }}]
                }}""");

        assertEquals(1, leaves.size(), "leaves: " + leaves);
        assertEquals("pack:item/bundle", leaves.get(0).modelRef());
        assertEquals(1, leaves.get(0).predicates().size(), "the custom model data match above the condition stays");
    }

    @Test
    void anUnsupportedConditionWithoutAnElseBranchMapsNothing() {
        assertTrue(walk("""
                {"model": {
                  "type": "minecraft:condition",
                  "property": "minecraft:selected",
                  "on_true": {"type": "minecraft:model", "model": "pack:item/tool"}
                }}""").isEmpty());
    }
}
