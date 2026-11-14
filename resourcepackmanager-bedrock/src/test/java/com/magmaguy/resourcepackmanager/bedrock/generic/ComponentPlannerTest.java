package com.magmaguy.resourcepackmanager.bedrock.generic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How the components of the custom items on one model become Geyser definitions. */
final class ComponentPlannerTest {

    private static final String NETHER_BRICK_PROTOTYPE = "{\"minecraft:max_stack_size\":64,\"minecraft:item_model\":\"minecraft:nether_brick\"}";
    private static final String APPLE_PROTOTYPE = "{\"minecraft:max_stack_size\":64,"
            + "\"minecraft:food\":{\"nutrition\":4,\"saturation\":2.4},\"minecraft:consumable\":{}}";
    private static final String FOOD = "\"minecraft:food\":{\"nutrition\":6,\"saturation\":7.2},\"minecraft:consumable\":{}";

    private final List<String> notes = new ArrayList<>();

    private static ItemComponentSnapshot item(String id, String patch) {
        return item(id, patch, NETHER_BRICK_PROTOTYPE);
    }

    private static ItemComponentSnapshot item(String id, String patch, String prototype) {
        JsonObject patchJson = JsonParser.parseString("{\"minecraft:item_model\":\"default:shared\"" + (patch.isEmpty() ? "" : "," + patch) + "}").getAsJsonObject();
        return new ItemComponentSnapshot("CraftEngine", id, "minecraft:nether_brick", patchJson,
                JsonParser.parseString(prototype).getAsJsonObject());
    }

    private ComponentPlan.Variant only(ComponentPlan plan) {
        assertEquals(1, plan.variants().size(), "variants: " + plan.variants());
        ComponentPlan.Variant variant = plan.variants().get(0);
        assertTrue(variant.extraPredicates().isEmpty());
        return variant;
    }

    @Test
    void oneItemCarriesItsComponents() {
        ComponentPlan.Variant variant = only(ComponentPlanner.plan(List.of(item("default:tomato",
                "\"minecraft:max_stack_size\":16," + FOOD + ",\"minecraft:custom_name\":\"x\"")), null, true, notes));

        assertEquals("{\"minecraft:max_stack_size\":16,"
                        + "\"minecraft:consumable\":{\"consume_seconds\":1.6,\"animation\":\"eat\"},"
                        + "\"minecraft:food\":{\"nutrition\":6,\"saturation\":7.2,\"can_always_eat\":false}}",
                variant.components().toString());
        assertTrue(variant.bedrockOptions().isEmpty());
        assertTrue(notes.isEmpty());
    }

    @Test
    void itemsThatAgreeShareTheirComponentsAndStackToTheSmallestLimit() {
        ComponentPlan.Variant variant = only(ComponentPlanner.plan(List.of(
                item("default:a", "\"minecraft:max_stack_size\":16," + FOOD),
                item("default:b", "\"minecraft:max_stack_size\":8," + FOOD)), null, true, notes));

        assertEquals(8, variant.components().get("minecraft:max_stack_size").getAsInt());
        assertTrue(variant.components().has("minecraft:food"));
        assertTrue(notes.isEmpty());
    }

    @Test
    void conflictingValuesAreLeftOutWithANote() {
        ComponentPlan.Variant variant = only(ComponentPlanner.plan(List.of(
                item("default:a", FOOD),
                item("default:b", "\"minecraft:food\":{\"nutrition\":1,\"saturation\":0.5},\"minecraft:consumable\":{}")),
                null, true, notes));

        assertFalse(variant.components().has("minecraft:food"));
        assertTrue(variant.components().has("minecraft:consumable"), "they agree on consumable");
        assertEquals(1, notes.size());
        assertTrue(notes.get(0).contains("minecraft:food differs") && notes.get(0).contains("default:a, default:b"), notes.get(0));
    }

    @Test
    void itemsThatDifferInWhichComponentsTheyHaveSplitByHasComponent() {
        ComponentPlan plan = ComponentPlanner.plan(List.of(
                item("default:pie", FOOD),
                item("default:brick_a", ""),
                item("default:brick_b", "\"minecraft:max_stack_size\":16")), null, true, notes);

        assertEquals(2, plan.variants().size());
        ComponentPlan.Variant edible = plan.variants().get(0);
        assertEquals("[{\"type\":\"condition\",\"property\":\"has_component\",\"expected\":true,\"component\":\"minecraft:consumable\"},"
                        + " {\"type\":\"condition\",\"property\":\"has_component\",\"expected\":true,\"component\":\"minecraft:food\"}]",
                edible.extraPredicates().stream().map(PredicateRecord::toGeyserJson).toList().toString());
        assertTrue(edible.components().has("minecraft:food"));
        assertFalse(edible.components().has("minecraft:max_stack_size"), "only the pie is in this group");

        ComponentPlan.Variant fallback = plan.variants().get(1);
        assertTrue(fallback.extraPredicates().isEmpty(), "the larger group catches every other stack");
        assertFalse(fallback.components().has("minecraft:food"));
        // brick_a keeps the base 64 and brick_b sets 16: Bedrock never stacks above the server.
        assertEquals("{\"minecraft:max_stack_size\":16}", fallback.components().toString());
    }

    @Test
    void legacyCustomModelDataMappingsCannotSplit() {
        ComponentPlan.Variant variant = only(ComponentPlanner.plan(List.of(
                item("default:pie", FOOD),
                item("default:brick", "")), null, false, notes));

        assertTrue(variant.components().isEmpty());
        assertEquals(1, notes.size());
        assertTrue(notes.get(0).contains("custom-model-data"), notes.get(0));
    }

    @Test
    void tooManySplittingComponentsAreAllLeftOut() {
        ComponentPlan.Variant variant = only(ComponentPlanner.plan(List.of(
                item("default:all", FOOD + ",\"minecraft:max_damage\":100,\"minecraft:use_cooldown\":{\"seconds\":1},"
                        + "\"minecraft:enchantment_glint_override\":true,\"minecraft:max_stack_size\":1"),
                item("default:none", "\"minecraft:max_stack_size\":1")), null, true, notes));

        assertEquals("{\"minecraft:max_stack_size\":1}", variant.components().toString());
        assertEquals(1, notes.size());
    }

    @Test
    void removingABaseComponentIsWrittenAsARemoval() {
        ComponentPlan.Variant variant = only(ComponentPlanner.plan(List.of(item("default:wax_apple",
                "\"!minecraft:food\":{},\"!minecraft:consumable\":{}", APPLE_PROTOTYPE)), null, true, notes));

        assertEquals("{\"!minecraft:consumable\":{},\"!minecraft:food\":{}}", variant.components().toString());
    }

    @Test
    void wornArmorGetsItsProtectionAndSlot() {
        ComponentPlan.Variant variant = only(ComponentPlanner.plan(List.of(item("default:crown",
                "\"minecraft:equippable\":{\"slot\":\"head\",\"asset_id\":\"default:crown\"},"
                        + "\"minecraft:attribute_modifiers\":[{\"type\":\"minecraft:armor\",\"amount\":2,"
                        + "\"operation\":\"add_value\",\"slot\":\"head\",\"id\":\"default:crown\"}],"
                        + "\"minecraft:max_stack_size\":1")), null, true, notes));

        assertEquals("{\"slot\":\"head\"}", variant.components().get("minecraft:equippable").toString());
        assertEquals(2, variant.bedrockOptions().get("protection_value").getAsInt());
    }

    @Test
    void aHatWithoutArmorGetsNoProtection() {
        ComponentPlan.Variant variant = only(ComponentPlanner.plan(List.of(item("default:hat",
                "\"minecraft:equippable\":{\"slot\":\"head\"}")), null, true, notes));

        assertTrue(variant.components().has("minecraft:equippable"));
        assertFalse(variant.bedrockOptions().has("protection_value"));
    }

    @Test
    void dyeTintedModelsAreDyeable() {
        ComponentPlan.Variant dyed = only(ComponentPlanner.plan(List.of(item("default:cap",
                "\"minecraft:dyed_color\":16711680")), 0xA06540, true, notes));
        assertEquals("{\"default_color\":\"#FF0000\"}", dyed.bedrockOptions().get("dyeable").toString());

        ComponentPlan.Variant tintOnly = only(ComponentPlanner.plan(List.of(), 0xA06540, true, notes));
        assertEquals("{\"default_color\":\"#A06540\"}", tintOnly.bedrockOptions().get("dyeable").toString());

        ComponentPlan.Variant untinted = only(ComponentPlanner.plan(List.of(item("default:cap",
                "\"minecraft:dyed_color\":16711680")), null, true, notes));
        assertFalse(untinted.bedrockOptions().has("dyeable"), "a whole icon would be tinted");
    }

    @Test
    void attackRangeNeedsAKineticOrPiercingWeapon() {
        String range = "\"minecraft:attack_range\":{\"max_reach\":4}";
        assertFalse(only(ComponentPlanner.plan(List.of(item("default:sword", range)), null, true, notes))
                .components().has("minecraft:attack_range"));
        assertTrue(only(ComponentPlanner.plan(List.of(item("default:spear", range + ",\"minecraft:piercing_weapon\":{}")),
                null, true, notes)).components().has("minecraft:attack_range"));
    }

    @Test
    void valuesGeyserCannotExpressAreLeftOutQuietly() {
        ComponentPlan.Variant variant = only(ComponentPlanner.plan(List.of(item("default:ring",
                "\"minecraft:equippable\":{\"slot\":\"mainhand\"},\"minecraft:max_stack_size\":1")), null, true, notes));

        assertEquals("{\"minecraft:max_stack_size\":1}", variant.components().toString());
        assertTrue(notes.isEmpty());
    }

    @Test
    void aModelNoPluginRegisteredIsUnchanged() {
        assertSame(ComponentPlan.NONE, ComponentPlanner.plan(List.of(), null, true, notes));
    }
}
