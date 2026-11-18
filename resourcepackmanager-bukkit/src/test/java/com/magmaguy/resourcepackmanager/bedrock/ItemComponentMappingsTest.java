package com.magmaguy.resourcepackmanager.bedrock;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.magmaguy.resourcepackmanager.bedrock.generic.ItemComponentSnapshot;
import com.magmaguy.resourcepackmanager.bedrock.items.CanonicalJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RSPM#21: Bedrock fixes an item's properties when the player joins, so a custom food on
 * nether_brick could not be eaten. The Geyser mapping of a custom item's model now carries the
 * components the item changes, read from the items the plugins register.
 */
final class ItemComponentMappingsTest {

    private static final String PROTOTYPE = "{\"minecraft:max_stack_size\":64,\"minecraft:item_model\":\"minecraft:nether_brick\"}";
    private static final String BASE = "minecraft:nether_brick";

    @TempDir
    Path temp;

    private static ItemComponentSnapshot item(String id, String model, String patch) {
        JsonObject components = JsonParser.parseString("{" + patch + "}").getAsJsonObject();
        components.addProperty("minecraft:item_model", model);
        return new ItemComponentSnapshot("CraftEngine", id, BASE, components, JsonParser.parseString(PROTOTYPE).getAsJsonObject());
    }

    private static List<JsonObject> definitions(JsonObject mappings, String base, String model) {
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement definition : mappings.getAsJsonObject("items").getAsJsonArray(base)) {
            if (model.equals(definition.getAsJsonObject().get("model").getAsString())) out.add(definition.getAsJsonObject());
        }
        return out;
    }

    @Test
    void customItemsCarryTheirComponentsUnderTheirOwnBase() throws Exception {
        List<String> models = List.of("tomato", "crown", "pie");
        Map<String, List<String>> declared = Map.of(
                "default:tomato", List.of(BASE), "default:crown", List.of(BASE), "default:pie", List.of(BASE));
        List<ItemComponentSnapshot> items = List.of(
                item("default:tomato", "default:tomato", "\"minecraft:max_stack_size\":16,"
                        + "\"minecraft:food\":{\"nutrition\":3,\"saturation\":1.2},\"minecraft:consumable\":{\"consume_seconds\":0.8}"),
                item("default:crown", "default:crown", "\"minecraft:max_stack_size\":1,"
                        + "\"minecraft:equippable\":{\"slot\":\"head\"},\"minecraft:attribute_modifiers\":[{\"type\":\"minecraft:armor\","
                        + "\"amount\":2,\"operation\":\"add_value\",\"slot\":\"head\",\"id\":\"default:crown\"}]"),
                item("default:pie", "default:pie", "\"minecraft:food\":{\"nutrition\":8,\"saturation\":4.8},\"minecraft:consumable\":{}"),
                item("default:pie_display", "default:pie", ""));

        JsonObject mappings = BedrockMappingFixture.mappings(temp, "default", models, declared, items);
        JsonObject plain = BedrockMappingFixture.mappings(temp, "default", models, declared, List.of());

        List<JsonObject> tomato = definitions(mappings, BASE, "default:tomato");
        assertEquals(1, tomato.size());
        assertEquals("{\"minecraft:consumable\":{\"animation\":\"eat\",\"consume_seconds\":0.8},"
                        + "\"minecraft:food\":{\"can_always_eat\":false,\"nutrition\":3,\"saturation\":1.2},"
                        + "\"minecraft:max_stack_size\":16}",
                CanonicalJson.write(tomato.get(0).get("components")));
        assertEquals(definitions(plain, BASE, "default:tomato").get(0).get("bedrock_identifier"),
                tomato.get(0).get("bedrock_identifier"), "a definition without extra predicates keeps its Bedrock id");
        assertEquals(java.util.Set.of(BASE), mappings.getAsJsonObject("items").keySet(),
                "no item on paper uses the models, so their declared base is the only one mapped");

        JsonObject crown = definitions(mappings, BASE, "default:crown").get(0);
        assertEquals("{\"slot\":\"head\"}", crown.getAsJsonObject("components").get("minecraft:equippable").toString());
        assertEquals(2, crown.getAsJsonObject("bedrock_options").get("protection_value").getAsInt());

        List<JsonObject> pie = definitions(mappings, BASE, "default:pie");
        assertEquals(2, pie.size(), "the edible pie and the display pie need separate definitions: " + pie);
        JsonObject edible = pie.stream().filter(definition -> definition.has("predicate")).findFirst().orElseThrow();
        JsonObject display = pie.stream().filter(definition -> !definition.has("predicate")).findFirst().orElseThrow();
        assertTrue(edible.get("predicate").toString().contains("\"property\":\"has_component\""), edible.toString());
        assertTrue(edible.getAsJsonObject("components").has("minecraft:food"));
        assertFalse(display.has("components"), display.toString());
        assertTrue(!edible.get("bedrock_identifier").equals(display.get("bedrock_identifier")));
    }
}
