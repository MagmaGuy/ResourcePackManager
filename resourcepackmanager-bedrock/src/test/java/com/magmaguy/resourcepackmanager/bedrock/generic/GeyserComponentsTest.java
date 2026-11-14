package com.magmaguy.resourcepackmanager.bedrock.generic;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Geyser drops a whole definition when one component fails its v2 reader, so every value is
 * clamped or left out here. The bounds are Geyser's DataComponentReaders'.
 */
final class GeyserComponentsTest {

    private static JsonElement json(String text) {
        return JsonParser.parseString(text);
    }

    private static JsonObject sanitized(String component, String java) {
        Optional<JsonElement> out = GeyserComponents.sanitize(component, json(java));
        assertTrue(out.isPresent(), component + " " + java + " should be kept");
        return out.get().isJsonObject() ? out.get().getAsJsonObject() : wrap(out.get());
    }

    private static JsonObject wrap(JsonElement primitive) {
        JsonObject out = new JsonObject();
        out.add("value", primitive);
        return out;
    }

    private static void leftOut(String component, String java) {
        assertTrue(GeyserComponents.sanitize(component, json(java)).isEmpty(), component + " " + java + " should be left out");
    }

    @Test
    void stackSizeIsClampedToWhatBedrockStacks() {
        assertEquals(16, sanitized("minecraft:max_stack_size", "16").get("value").getAsInt());
        assertEquals(99, sanitized("minecraft:max_stack_size", "120").get("value").getAsInt());
        assertEquals(1, sanitized("minecraft:max_stack_size", "0").get("value").getAsInt());
        leftOut("minecraft:max_stack_size", "\"sixteen\"");
    }

    @Test
    void consumableFillsJavaDefaultsAndKeepsOnlyGeyserAnimations() {
        JsonObject food = sanitized("minecraft:consumable", "{}");
        assertEquals(1.6F, food.get("consume_seconds").getAsFloat());
        assertEquals("eat", food.get("animation").getAsString());

        JsonObject horn = sanitized("minecraft:consumable",
                "{\"consume_seconds\":0,\"animation\":\"minecraft:toot_horn\",\"sound\":\"x\",\"on_consume_effects\":[]}");
        assertEquals(GeyserComponents.MIN_CONSUME_SECONDS, horn.get("consume_seconds").getAsFloat());
        assertFalse(horn.has("animation"), "Geyser has no toot_horn animation");
        assertEquals(1, horn.size(), "only the fields Geyser reads: " + horn);

        assertEquals("drink", sanitized("minecraft:consumable", "{\"animation\":\"drink\"}").get("animation").getAsString());
    }

    @Test
    void foodKeepsNutritionSaturationAndAlwaysEat() {
        JsonObject food = sanitized("minecraft:food", "{\"nutrition\":4,\"saturation\":2.4}");
        assertEquals(4, food.get("nutrition").getAsInt());
        assertEquals(2.4F, food.get("saturation").getAsFloat());
        assertFalse(food.get("can_always_eat").getAsBoolean());

        JsonObject negative = sanitized("minecraft:food", "{\"nutrition\":-2,\"saturation\":-1,\"can_always_eat\":true}");
        assertEquals(0, negative.get("nutrition").getAsInt());
        assertEquals(0F, negative.get("saturation").getAsFloat());
        assertTrue(negative.get("can_always_eat").getAsBoolean());

        leftOut("minecraft:food", "\"apple\"");
    }

    @Test
    void equippableKeepsOnlySlotsBedrockWears() {
        JsonObject hat = sanitized("minecraft:equippable",
                "{\"slot\":\"head\",\"asset_id\":\"default:crown\",\"equip_sound\":\"minecraft:item.armor.equip_gold\"}");
        assertEquals("{\"slot\":\"head\"}", hat.toString());
        assertEquals("saddle", sanitized("minecraft:equippable", "{\"slot\":\"saddle\"}").get("slot").getAsString());
        leftOut("minecraft:equippable", "{\"slot\":\"mainhand\"}");
        leftOut("minecraft:equippable", "{}");
    }

    @Test
    void cooldownNeedsPositiveSeconds() {
        JsonObject cooldown = sanitized("minecraft:use_cooldown", "{\"seconds\":2.5,\"cooldown_group\":\"default:wands\"}");
        assertEquals(2.5F, cooldown.get("seconds").getAsFloat());
        assertEquals("default:wands", cooldown.get("cooldown_group").getAsString());
        leftOut("minecraft:use_cooldown", "{\"seconds\":0}");
    }

    @Test
    void enchantableAcceptsBothJavaShapes() {
        assertEquals("{\"value\":10}", sanitized("minecraft:enchantable", "{\"value\":10}").toString());
        assertEquals("{\"value\":15}", sanitized("minecraft:enchantable", "15").toString());
    }

    @Test
    void repairableTakesAnItemATagOrAListOfItems() {
        assertEquals("{\"items\":\"minecraft:diamond\"}",
                sanitized("minecraft:repairable", "{\"items\":\"minecraft:diamond\"}").toString());
        assertEquals("{\"items\":\"#minecraft:planks\"}",
                sanitized("minecraft:repairable", "{\"items\":\"#minecraft:planks\"}").toString());
        assertEquals("{\"items\":[\"minecraft:iron_ingot\",\"minecraft:gold_ingot\"]}",
                sanitized("minecraft:repairable", "{\"items\":[\"minecraft:iron_ingot\",\"minecraft:gold_ingot\"]}").toString());
        leftOut("minecraft:repairable", "{\"items\":[\"#minecraft:planks\"]}");
        leftOut("minecraft:repairable", "{\"items\":[]}");
    }

    @Test
    void toolIsWrittenOnlyWhenItStopsCreativeBreaking() {
        leftOut("minecraft:tool", "{\"rules\":[],\"default_mining_speed\":4}");
        assertEquals("{\"can_destroy_blocks_in_creative\":false}",
                sanitized("minecraft:tool", "{\"rules\":[],\"can_destroy_blocks_in_creative\":false}").toString());
    }

    @Test
    void smallerComponentsAreClamped() {
        assertTrue(sanitized("minecraft:enchantment_glint_override", "true").get("value").getAsBoolean());
        leftOut("minecraft:enchantment_glint_override", "1");
        assertEquals(1F, sanitized("minecraft:use_effects", "{\"speed_multiplier\":2}").get("speed_multiplier").getAsFloat());
        assertEquals(0.2F, sanitized("minecraft:use_effects", "{}").get("speed_multiplier").getAsFloat());
        assertEquals(1, sanitized("minecraft:swing_animation", "{\"duration\":0}").get("duration").getAsInt());
        assertEquals("{}", sanitized("minecraft:piercing_weapon", "{\"deals_knockback\":false}").toString());

        JsonObject range = sanitized("minecraft:attack_range", "{\"max_reach\":100,\"hitbox_margin\":3}");
        assertEquals(64F, range.get("max_reach").getAsFloat());
        assertEquals(1F, range.get("hitbox_margin").getAsFloat());
        assertEquals(5F, range.get("max_creative_reach").getAsFloat());

        JsonObject lance = sanitized("minecraft:kinetic_weapon",
                "{\"delay_ticks\":-3,\"dismount_conditions\":{\"max_duration_ticks\":40,\"min_speed\":0.5}}");
        assertEquals(0, lance.get("delay_ticks").getAsInt());
        assertEquals(40, lance.getAsJsonObject("dismount_conditions").get("max_duration_ticks").getAsInt());
    }

    @Test
    void componentsGeyserCannotReadAreNeverWritten() {
        leftOut("minecraft:attack_animation", "\"stab\"");
        leftOut("minecraft:compostable", "0.5");
        leftOut("minecraft:item_name", "\"Topaz\"");
        assertFalse(GeyserComponents.SUPPORTED.contains("minecraft:cooking_fuel"));
    }

    @Test
    void protectionComesFromAddedArmorOnArmorSlots() {
        String modern = "[{\"type\":\"minecraft:armor\",\"amount\":2.6,\"operation\":\"add_value\",\"slot\":\"head\",\"id\":\"a\"},"
                + "{\"type\":\"minecraft:armor_toughness\",\"amount\":2,\"operation\":\"add_value\",\"slot\":\"head\",\"id\":\"b\"},"
                + "{\"type\":\"minecraft:armor\",\"amount\":5,\"operation\":\"add_value\",\"slot\":\"mainhand\",\"id\":\"c\"},"
                + "{\"type\":\"minecraft:armor\",\"amount\":0.5,\"operation\":\"add_multiplied_base\",\"slot\":\"head\",\"id\":\"d\"}]";
        assertEquals(3, GeyserComponents.protectionValue(json(modern)));

        String older = "{\"modifiers\":[{\"type\":\"minecraft:armor\",\"amount\":4,\"operation\":\"add_value\",\"slot\":\"chest\",\"id\":\"a\"}],"
                + "\"show_in_tooltip\":false}";
        assertEquals(4, GeyserComponents.protectionValue(json(older)));
        assertEquals(0, GeyserComponents.protectionValue(null));
    }
}
