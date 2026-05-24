package com.magmaguy.resourcepackmanager.bedrock.generic;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Infers an armor slot from an equipment file's {@code layers} keys plus filename hints,
 * then maps slot → list of vanilla base armor item identifiers.
 *
 * <p>Per the Minecraft Wiki Equipment page, equipment JSON contains {@code layers} but no
 * {@code slot} field. The Option J plan's algorithm is therefore best-effort:
 * <ul>
 *   <li>{@code humanoid_leggings} → legs</li>
 *   <li>{@code wings} → chest (elytra-bearing)</li>
 *   <li>{@code humanoid} alone → ambiguous (head/chest/feet); combine with filename hint</li>
 *   <li>{@code humanoid_baby} → all four slots (rare)</li>
 * </ul>
 */
public final class EquipmentSlotMapper {

    public enum Slot { HEAD, CHEST, LEGS, FEET }

    /** Base-item lists per slot. Plan: head=7 (incl turtle), chest=7 (incl elytra), legs=6, feet=6. */
    private static final List<String> HEAD_BASES = List.of(
            "minecraft:leather_helmet", "minecraft:chainmail_helmet", "minecraft:iron_helmet",
            "minecraft:golden_helmet", "minecraft:diamond_helmet", "minecraft:netherite_helmet",
            "minecraft:turtle_helmet"
    );
    private static final List<String> CHEST_BASES = List.of(
            "minecraft:leather_chestplate", "minecraft:chainmail_chestplate", "minecraft:iron_chestplate",
            "minecraft:golden_chestplate", "minecraft:diamond_chestplate", "minecraft:netherite_chestplate",
            "minecraft:elytra"
    );
    private static final List<String> LEGS_BASES = List.of(
            "minecraft:leather_leggings", "minecraft:chainmail_leggings", "minecraft:iron_leggings",
            "minecraft:golden_leggings", "minecraft:diamond_leggings", "minecraft:netherite_leggings"
    );
    private static final List<String> FEET_BASES = List.of(
            "minecraft:leather_boots", "minecraft:chainmail_boots", "minecraft:iron_boots",
            "minecraft:golden_boots", "minecraft:diamond_boots", "minecraft:netherite_boots"
    );

    private EquipmentSlotMapper() {}

    /**
     * Given a parsed equipment JSON and a filename stem, infer the most likely slot.
     * Returns empty if the equipment file's layers don't match any known pattern.
     */
    public static Optional<Slot> inferSlot(JsonObject equipmentJson, String filenameStem) {
        if (equipmentJson == null) return Optional.empty();
        JsonObject layers = equipmentJson.has("layers") && equipmentJson.get("layers").isJsonObject()
                ? equipmentJson.getAsJsonObject("layers")
                : null;
        if (layers == null) return Optional.empty();

        Set<String> layerKeys = layers.keySet();
        String lower = filenameStem == null ? "" : filenameStem.toLowerCase();

        // Trust filename FIRST when it explicitly identifies the armor slot.
        // Many plugins (e.g. EliteMobs) ship one equipment file per tier (bronze.json)
        // that covers chestplate+leggings+boots simultaneously, with both `humanoid` AND
        // `humanoid_leggings` layers present. If we checked layers first, the
        // `humanoid_leggings` check would short-circuit and every armor piece (helmets,
        // boots, chestplates) would be misrouted to leggings.
        if (lower.contains("helmet") || lower.contains("cap") || lower.contains("hood") || lower.contains("crown"))
            return Optional.of(Slot.HEAD);
        if (lower.contains("chestplate") || lower.contains("tunic") || lower.contains("cloak") || lower.contains("robe"))
            return Optional.of(Slot.CHEST);
        if (lower.contains("boots") || lower.contains("shoe"))
            return Optional.of(Slot.FEET);
        if (lower.contains("leggings") || lower.contains("pants") || lower.contains("trousers"))
            return Optional.of(Slot.LEGS);
        if (lower.contains("elytra") || lower.contains("wings"))
            return Optional.of(Slot.CHEST);

        // Fall back to layer-key inference when the filename gave no clue.
        if (layerKeys.contains("humanoid_leggings")) return Optional.of(Slot.LEGS);
        if (layerKeys.contains("wings")) return Optional.of(Slot.CHEST);
        if (layerKeys.contains("humanoid_baby")) return Optional.of(Slot.HEAD); // rare; pick head as primary
        if (layerKeys.contains("humanoid")) {
            // Pure-humanoid file with no filename hint — default to CHEST (most common).
            return Optional.of(Slot.CHEST);
        }
        return Optional.empty();
    }

    public static List<String> baseItemsFor(Slot slot) {
        return switch (slot) {
            case HEAD -> HEAD_BASES;
            case CHEST -> CHEST_BASES;
            case LEGS -> LEGS_BASES;
            case FEET -> FEET_BASES;
        };
    }
}
