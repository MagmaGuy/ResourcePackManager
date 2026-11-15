package com.magmaguy.resourcepackmanager.bedrock.generic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The Geyser mappings file carries each custom item's components in Geyser's v2 layout. */
final class ComponentMappingOutputTest {

    @TempDir
    Path temp;

    private JsonObject write(MappedItemRegistry registry) throws Exception {
        File out = temp.resolve("geyser_mappings.json").toFile();
        GenericGeyserMappingBuilder.merge(registry, out);
        return JsonParser.parseString(Files.readString(out.toPath())).getAsJsonObject();
    }

    @Test
    void componentsFollowBedrockOptionsAndPrecedeTheModel() throws Exception {
        JsonObject components = JsonParser.parseString(
                "{\"minecraft:max_stack_size\":1,\"minecraft:equippable\":{\"slot\":\"head\"}}").getAsJsonObject();
        JsonObject options = JsonParser.parseString(
                "{\"protection_value\":2,\"dyeable\":{\"default_color\":\"#A06540\"}}").getAsJsonObject();
        MappedItemRegistry registry = new MappedItemRegistry();
        registry.addMapping("minecraft:leather_helmet", GeyserDefinitionEntry.definition(
                "rspm:crown", "default:crown", List.of(), "rspm_crown", false).withItemData(components, options));

        JsonObject definition = write(registry).getAsJsonObject("items")
                .getAsJsonArray("minecraft:leather_helmet").get(0).getAsJsonObject();

        assertEquals(List.of("type", "bedrock_identifier", "bedrock_options", "components", "model"),
                List.copyOf(definition.keySet()));
        assertEquals("{\"icon\":\"rspm_crown\",\"tags\":[\"minecraft:is_armor\"],"
                        + "\"protection_value\":2,\"dyeable\":{\"default_color\":\"#A06540\"}}",
                definition.get("bedrock_options").toString());
        assertEquals(components, definition.get("components"));
    }

    @Test
    void hasComponentVariantsAreSeparateDefinitionsAndTheFallbackComesLast() throws Exception {
        PredicateRecord edible = new PredicateRecord.Condition("has_component", true,
                Map.of("component", new com.google.gson.JsonPrimitive("minecraft:food")));
        MappedItemRegistry registry = new MappedItemRegistry();
        registry.addMapping("minecraft:nether_brick", GeyserDefinitionEntry.definition(
                "rspm:pie_food", "default:pie", List.of(edible), "rspm_pie", false)
                .withItemData(JsonParser.parseString("{\"minecraft:food\":{\"nutrition\":6,\"saturation\":7.2,\"can_always_eat\":false}}")
                        .getAsJsonObject(), null));
        registry.addMapping("minecraft:nether_brick", GeyserDefinitionEntry.definition(
                "rspm:pie", "default:pie", List.of(), "rspm_pie", false));

        List<JsonObject> definitions = write(registry).getAsJsonObject("items").getAsJsonArray("minecraft:nether_brick")
                .asList().stream().map(element -> element.getAsJsonObject()).toList();

        assertEquals(2, definitions.size());
        assertEquals("rspm:pie", definitions.get(0).get("bedrock_identifier").getAsString(),
                "the merged file sorts by predicate count; Geyser itself tries more specific definitions first");
        assertFalse(definitions.get(0).has("components"), "a definition with no item data writes no components");
        assertEquals("[{\"type\":\"condition\",\"property\":\"has_component\",\"expected\":true,\"component\":\"minecraft:food\"}]",
                definitions.get(1).get("predicate").toString());
    }

    @Test
    void legacyDefinitionsCarryComponentsToo() throws Exception {
        MappedItemRegistry registry = new MappedItemRegistry();
        registry.addMapping("minecraft:paper", GeyserDefinitionEntry.legacy(
                "rspm:ruby", java.math.BigDecimal.valueOf(1001), List.of(), "rspm_ruby", false)
                .withItemData(JsonParser.parseString("{\"minecraft:max_stack_size\":16}").getAsJsonObject(), null));

        JsonObject definition = write(registry).getAsJsonObject("items")
                .getAsJsonArray("minecraft:paper").get(0).getAsJsonObject();

        assertEquals(List.of("type", "custom_model_data", "bedrock_identifier", "bedrock_options", "components"),
                List.copyOf(definition.keySet()));
    }
}
