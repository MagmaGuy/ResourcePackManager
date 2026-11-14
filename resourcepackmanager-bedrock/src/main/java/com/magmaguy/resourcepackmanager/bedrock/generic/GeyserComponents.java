package com.magmaguy.resourcepackmanager.bedrock.generic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Turns Java component JSON, as the server's codecs write it, into the form Geyser's custom
 * item v2 reader accepts.
 *
 * <p>Geyser rejects a whole definition when one component fails its reader, so every value
 * is checked against the reader's bounds and enums here, and only the fields the reader reads
 * are kept. Java's codecs leave out fields at their default; the defaults below are Java's,
 * which is what the item has in game. A component Geyser cannot express is left out, so the
 * item keeps its base item's behaviour for it, as before.</p>
 *
 * <p>Only components Geyser has read since 2.9.3, the first release with v2 mappings, are
 * handled. {@code attack_animation}, {@code compostable} and {@code cooking_fuel} need Geyser
 * 2.12, and RSPM cannot tell which Geyser a proxy runs, so they are never written.</p>
 */
public final class GeyserComponents {

    /** Components in the order they are written. */
    public static final List<String> SUPPORTED = List.of(
            "minecraft:max_stack_size",
            "minecraft:max_damage",
            "minecraft:consumable",
            "minecraft:food",
            "minecraft:equippable",
            "minecraft:use_cooldown",
            "minecraft:enchantable",
            "minecraft:repairable",
            "minecraft:enchantment_glint_override",
            "minecraft:tool",
            "minecraft:use_effects",
            "minecraft:kinetic_weapon",
            "minecraft:piercing_weapon",
            "minecraft:attack_range",
            "minecraft:swing_animation");

    /** Geyser's JavaConsumable.Animation values. Java's toot_horn, bundle and trident are not among them. */
    private static final Set<String> CONSUMABLE_ANIMATIONS = Set.of(
            "none", "eat", "drink", "block", "bow", "spear", "crossbow", "spyglass", "brush");
    /** Geyser's JavaEquippable.EquipmentSlot values. */
    private static final Set<String> EQUIPMENT_SLOTS = Set.of("head", "chest", "legs", "feet", "body", "saddle");
    private static final Set<String> ARMOR_SLOTS = Set.of("head", "chest", "legs", "feet", "body");
    /** Shortest consume time Geyser accepts; Java allows 0 (instant). */
    static final float MIN_CONSUME_SECONDS = 0.05F;

    private GeyserComponents() {
    }

    /**
     * @param component a component id from {@link #SUPPORTED}
     * @param value     its Java JSON
     * @return the Geyser JSON, or empty when Geyser cannot express this value
     */
    public static Optional<JsonElement> sanitize(String component, JsonElement value) {
        if (value == null || value.isJsonNull()) return Optional.empty();
        try {
            return Optional.ofNullable(switch (component) {
                case "minecraft:max_stack_size" -> integer(value, 1, 99);
                case "minecraft:max_damage" -> integer(value, 0, Integer.MAX_VALUE);
                case "minecraft:consumable" -> consumable(value);
                case "minecraft:food" -> food(value);
                case "minecraft:equippable" -> equippable(value);
                case "minecraft:use_cooldown" -> useCooldown(value);
                case "minecraft:enchantable" -> enchantable(value);
                case "minecraft:repairable" -> repairable(value);
                case "minecraft:enchantment_glint_override" ->
                        value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() ? value.deepCopy() : null;
                case "minecraft:tool" -> tool(value);
                case "minecraft:use_effects" -> useEffects(value);
                case "minecraft:kinetic_weapon" -> kineticWeapon(value);
                case "minecraft:piercing_weapon" -> new JsonObject();
                case "minecraft:attack_range" -> attackRange(value);
                case "minecraft:swing_animation" -> swingAnimation(value);
                default -> null;
            });
        } catch (RuntimeException malformed) {
            // A value shaped differently from what this server version's codec wrote before
            // costs only this component.
            return Optional.empty();
        }
    }

    /**
     * Sum of the {@code add_value} armor modifiers in a Java {@code attribute_modifiers}
     * component that apply to an armor slot, rounded for Bedrock's armor bar; 0 when none.
     * 1.21.4 writes {@code {"modifiers": [...]}}, 1.21.5 and newer a bare list.
     */
    public static int protectionValue(JsonElement attributeModifiers) {
        if (attributeModifiers == null) return 0;
        JsonArray modifiers;
        if (attributeModifiers.isJsonArray()) {
            modifiers = attributeModifiers.getAsJsonArray();
        } else if (attributeModifiers.isJsonObject()
                && attributeModifiers.getAsJsonObject().has("modifiers")
                && attributeModifiers.getAsJsonObject().get("modifiers").isJsonArray()) {
            modifiers = attributeModifiers.getAsJsonObject().getAsJsonArray("modifiers");
        } else {
            return 0;
        }
        double armor = 0;
        for (JsonElement element : modifiers) {
            if (!element.isJsonObject()) continue;
            JsonObject modifier = element.getAsJsonObject();
            if (!"armor".equals(stripNamespace(string(modifier, "type", "")))) continue;
            if (!"add_value".equals(string(modifier, "operation", ""))) continue;
            String slot = string(modifier, "slot", "any");
            if (!ARMOR_SLOTS.contains(slot) && !"armor".equals(slot) && !"any".equals(slot)) continue;
            try {
                armor += modifier.get("amount").getAsDouble();
            } catch (RuntimeException ignored) {
                // An unreadable amount adds nothing.
            }
        }
        return Math.max(0, (int) Math.round(armor));
    }

    /** True when the Java {@code equippable} component puts the item in an armor slot. */
    public static boolean isArmorSlot(JsonElement equippable) {
        return equippable != null && equippable.isJsonObject()
                && ARMOR_SLOTS.contains(string(equippable.getAsJsonObject(), "slot", ""));
    }

    private static JsonElement integer(JsonElement value, int min, int max) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
        long number = value.getAsLong();
        return new JsonPrimitive((int) Math.max(min, Math.min(max, number)));
    }

    private static JsonElement consumable(JsonElement value) {
        JsonObject java = value.getAsJsonObject();
        JsonObject out = new JsonObject();
        float seconds = java.has("consume_seconds") ? java.get("consume_seconds").getAsFloat() : 1.6F;
        if (!Float.isFinite(seconds)) return null;
        out.addProperty("consume_seconds", Math.max(MIN_CONSUME_SECONDS, seconds));
        String animation = stripNamespace(string(java, "animation", "eat")).toLowerCase(Locale.ROOT);
        // Geyser defaults to eat, which is also how Java animates most of the others.
        if (CONSUMABLE_ANIMATIONS.contains(animation)) out.addProperty("animation", animation);
        return out;
    }

    private static JsonElement food(JsonElement value) {
        JsonObject java = value.getAsJsonObject();
        JsonObject out = new JsonObject();
        out.addProperty("nutrition", Math.max(0, java.get("nutrition").getAsInt()));
        float saturation = java.get("saturation").getAsFloat();
        out.addProperty("saturation", Float.isFinite(saturation) ? Math.max(0F, saturation) : 0F);
        out.addProperty("can_always_eat", java.has("can_always_eat") && java.get("can_always_eat").getAsBoolean());
        return out;
    }

    private static JsonElement equippable(JsonElement value) {
        String slot = string(value.getAsJsonObject(), "slot", "").toLowerCase(Locale.ROOT);
        if (!EQUIPMENT_SLOTS.contains(slot)) return null;
        JsonObject out = new JsonObject();
        out.addProperty("slot", slot);
        return out;
    }

    private static JsonElement useCooldown(JsonElement value) {
        JsonObject java = value.getAsJsonObject();
        float seconds = java.get("seconds").getAsFloat();
        if (!Float.isFinite(seconds) || seconds <= 0F) return null;
        JsonObject out = new JsonObject();
        out.addProperty("seconds", seconds);
        if (java.has("cooldown_group") && java.get("cooldown_group").isJsonPrimitive()) {
            out.addProperty("cooldown_group", java.get("cooldown_group").getAsString());
        }
        return out;
    }

    private static JsonElement enchantable(JsonElement value) {
        int enchantability = value.isJsonPrimitive() ? value.getAsInt() : value.getAsJsonObject().get("value").getAsInt();
        JsonObject out = new JsonObject();
        out.addProperty("value", Math.max(0, enchantability));
        return out;
    }

    private static JsonElement repairable(JsonElement value) {
        JsonElement items = value.getAsJsonObject().get("items");
        if (items == null) return null;
        JsonObject out = new JsonObject();
        if (items.isJsonPrimitive()) {
            String id = items.getAsString();
            if (id.isBlank()) return null;
            out.addProperty("items", id);
            return out;
        }
        if (items.isJsonArray() && !items.getAsJsonArray().isEmpty()) {
            JsonArray ids = new JsonArray();
            for (JsonElement id : items.getAsJsonArray()) {
                if (!id.isJsonPrimitive() || id.getAsString().isBlank() || id.getAsString().startsWith("#")) return null;
                ids.add(id.getAsString());
            }
            out.add("items", ids);
            return out;
        }
        return null;
    }

    private static JsonElement tool(JsonElement value) {
        JsonObject java = value.getAsJsonObject();
        boolean creative = !java.has("can_destroy_blocks_in_creative")
                || java.get("can_destroy_blocks_in_creative").getAsBoolean();
        // Geyser's tool reader keeps only this flag and sets no mining rules or speed, so the
        // component is written only when it changes what Bedrock does: a tool that cannot
        // break blocks in creative, like a sword.
        if (creative) return null;
        JsonObject out = new JsonObject();
        out.addProperty("can_destroy_blocks_in_creative", false);
        return out;
    }

    private static JsonElement useEffects(JsonElement value) {
        JsonObject java = value.getAsJsonObject();
        float multiplier = java.has("speed_multiplier") ? java.get("speed_multiplier").getAsFloat() : 0.2F;
        if (!Float.isFinite(multiplier)) return null;
        JsonObject out = new JsonObject();
        out.addProperty("speed_multiplier", Math.max(0F, Math.min(1F, multiplier)));
        return out;
    }

    private static JsonElement kineticWeapon(JsonElement value) {
        JsonObject java = value.getAsJsonObject();
        JsonObject out = new JsonObject();
        out.addProperty("delay_ticks", Math.max(0, java.has("delay_ticks") ? java.get("delay_ticks").getAsInt() : 0));
        if (java.has("dismount_conditions") && java.get("dismount_conditions").isJsonObject()) {
            JsonObject condition = java.getAsJsonObject("dismount_conditions");
            JsonObject outCondition = new JsonObject();
            outCondition.addProperty("max_duration_ticks", Math.max(0, condition.get("max_duration_ticks").getAsInt()));
            if (condition.has("min_speed")) outCondition.addProperty("min_speed", finite(condition.get("min_speed").getAsFloat()));
            if (condition.has("min_relative_speed")) {
                outCondition.addProperty("min_relative_speed", finite(condition.get("min_relative_speed").getAsFloat()));
            }
            out.add("dismount_conditions", outCondition);
        }
        return out;
    }

    private static JsonElement attackRange(JsonElement value) {
        JsonObject java = value.getAsJsonObject();
        JsonObject out = new JsonObject();
        out.addProperty("min_reach", reach(java, "min_reach", 0F));
        out.addProperty("max_reach", reach(java, "max_reach", 3F));
        out.addProperty("min_creative_reach", reach(java, "min_creative_reach", 0F));
        out.addProperty("max_creative_reach", reach(java, "max_creative_reach", 5F));
        float margin = java.has("hitbox_margin") ? java.get("hitbox_margin").getAsFloat() : 0.3F;
        out.addProperty("hitbox_margin", Math.max(0F, Math.min(1F, finite(margin))));
        return out;
    }

    private static JsonElement swingAnimation(JsonElement value) {
        JsonObject java = value.getAsJsonObject();
        int duration = java.has("duration") ? java.get("duration").getAsInt() : 6;
        JsonObject out = new JsonObject();
        out.addProperty("duration", Math.max(1, duration));
        return out;
    }

    private static float reach(JsonObject java, String key, float fallback) {
        float reach = java.has(key) ? java.get(key).getAsFloat() : fallback;
        return Math.max(0F, Math.min(64F, finite(reach)));
    }

    private static float finite(float value) {
        return Float.isFinite(value) ? value : 0F;
    }

    private static String string(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }

    static String stripNamespace(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }
}
