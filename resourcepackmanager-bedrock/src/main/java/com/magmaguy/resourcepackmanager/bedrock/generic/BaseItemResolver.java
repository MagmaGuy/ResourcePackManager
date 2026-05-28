package com.magmaguy.resourcepackmanager.bedrock.generic;

import com.google.gson.JsonObject;
import com.magmaguy.resourcepackmanager.bedrock.BedrockLog;

import java.util.List;
import java.util.Optional;

/**
 * Resolves which vanilla base Java items a generic plugin's custom item_model should be
 * registered under in the Geyser mappings file. See Option J plan, "Equipment-aware armor
 * detection" and "Filename heuristic table".
 *
 * <p>Resolution order: filename heuristic for clearly-weapon/tool patterns →
 * equipment-file lookup (armor case) → broader filename heuristic → generic fallback.
 *
 * <p>The early weapon/tool heuristic precedes the equipment lookup specifically because
 * many plugins (e.g. EliteMobs) ship a single "material" equipment file (e.g.
 * {@code bronze.json}) that covers chestplate+leggings+boots for the same tier, and the
 * naive material-stem lookup would pull a sword named {@code bronze_sword} into the
 * leggings-slot equipment file (which has a {@code humanoid_leggings} layer) and route
 * the sword under leg-armor base items.
 */
public final class BaseItemResolver {

    private BaseItemResolver() {}

    /**
     * Base item that FreeMinecraftModels uses as the carrier for every bone armor stand
     * (see {@code PacketArmorStandEntity.initializeModel} in EasyMinecraftGoals — always
     * {@code Material.LEATHER_HORSE_ARMOR}).
     * Every items definition in the {@code freeminecraftmodels} namespace must have a
     * Geyser mapping under this base item too, otherwise Bedrock clients see plain
     * leather-horse-armor-wearing armor stands instead of the custom model. The
     * filename-heuristic-chosen base item ({@code bow}, {@code compass}, etc.) also
     * stays in the list so the same model still renders if a plugin equips it on a
     * player-held base item (e.g. FMM's {@code /craftify} command emits paper).
     */
    private static final String FMM_BONE_CARRIER = "minecraft:leather_horse_armor";

    /**
     * Resolves the candidate base-item list for a given items definition.
     * Never returns empty: falls through to {@link FilenameHeuristic#genericFallback()}.
     */
    public static List<String> resolve(ItemsDefinition def, AssetResolver resolver) {
        List<String> bases = resolveHeuristic(def, resolver);

        // FreeMinecraftModels always wears items on a leather_horse_armor carrier when
        // rendering bones via packet armor stands. Ensure the FMM model is registered
        // under that base item too, in addition to whatever the filename heuristic
        // picked (which covers the /craftify or held-item case). Without this the
        // bone armor stand renders as plain leather-horse-armor on Bedrock and the
        // user-visible FMM furniture / props / weapons are invisible.
        if ("freeminecraftmodels".equals(def.namespace()) && !bases.contains(FMM_BONE_CARRIER)) {
            // Build a fresh list so we don't mutate the (often-shared) heuristic-returned list.
            java.util.List<String> merged = new java.util.ArrayList<>(bases.size() + 1);
            merged.addAll(bases);
            merged.add(FMM_BONE_CARRIER);
            return merged;
        }
        return bases;
    }

    private static List<String> resolveHeuristic(ItemsDefinition def, AssetResolver resolver) {
        String filenameStem = lastPathSegment(def.itemsRelPath());
        String lower = filenameStem == null ? "" : filenameStem.toLowerCase();

        // 1. Early bail-out for clearly-weapon/tool filenames. Done BEFORE the
        //    equipment-file lookup so we don't misroute a sword through a tier's
        //    shared armor equipment file (see class javadoc).
        if (looksLikeWeaponOrTool(lower)) {
            Optional<List<String>> direct = FilenameHeuristic.match(filenameStem);
            if (direct.isPresent()) return direct.get();
        }

        // 2. Equipment-file lookup (armor case)
        Optional<EquipmentSlotMapper.Slot> equipmentSlot = tryEquipmentLookup(def, resolver, filenameStem);
        if (equipmentSlot.isPresent()) {
            return EquipmentSlotMapper.baseItemsFor(equipmentSlot.get());
        }

        // 3. Filename heuristic (broader pass — picks up armor patterns and remaining cases)
        Optional<List<String>> heuristic = FilenameHeuristic.match(filenameStem);
        if (heuristic.isPresent()) {
            return heuristic.get();
        }

        // 4. Generic fallback. Silent — items hitting this path are working
        // as designed (they just don't have a specific base-item rule). Logging
        // a WARN per item produced hundreds of lines per mix run with no
        // diagnostic value.
        return FilenameHeuristic.genericFallback();
    }

    /**
     * Returns true when the filename clearly identifies a weapon or tool — meaning
     * the equipment-file lookup must be skipped. Anything ambiguous (no clear
     * weapon/tool token) falls through to the equipment-file path.
     */
    private static boolean looksLikeWeaponOrTool(String lower) {
        return lower.contains("sword")
                || lower.contains("blade")
                || lower.contains("katana")
                || lower.contains("pickaxe")
                || (lower.contains("axe") && !lower.contains("pickaxe"))
                || lower.contains("shovel")
                || lower.contains("spade")
                || (lower.contains("hoe") && !lower.contains("shoe"))
                || lower.contains("crossbow")
                || (lower.contains("bow") && !lower.contains("elbow") && !lower.contains("rainbow"))
                || lower.contains("trident")
                || lower.contains("mace")
                || lower.contains("scythe")
                || lower.contains("pike")
                || lower.contains("spear")
                || lower.contains("lance")
                || lower.contains("staff")
                || lower.contains("wand")
                || lower.contains("scepter")
                || lower.contains("rod")
                || lower.contains("fishing")
                || lower.contains("shield");
    }

    private static Optional<EquipmentSlotMapper.Slot> tryEquipmentLookup(
            ItemsDefinition def, AssetResolver resolver, String filenameStem) {
        String ns = def.namespace();

        // Try filename-stem.json first (exact match — e.g. "bronze_chestplate.json")
        Optional<JsonObject> filenameEquip = resolver.getEquipment(ns + ":" + filenameStem);
        Optional<EquipmentSlotMapper.Slot> bySlot = filenameEquip.flatMap(j -> EquipmentSlotMapper.inferSlot(j, filenameStem));
        if (bySlot.isPresent()) return bySlot;

        // Try material-stem.json (drop last "_" segment — e.g. "bronze.json" for
        // "bronze_chestplate") but ONLY when the filename actually carries an
        // armor-suggesting suffix. Otherwise we'd wrongly conclude an arbitrary item
        // like "bronze_pickaxe" is leg armor just because a "bronze.json" equipment
        // file exists for that tier's chestplate/leggings/boots set.
        String lower = filenameStem.toLowerCase();
        boolean armorishSuffix = lower.contains("helmet") || lower.contains("cap")
                || lower.contains("hood") || lower.contains("crown")
                || lower.contains("chestplate") || lower.contains("tunic")
                || lower.contains("cloak") || lower.contains("robe")
                || lower.contains("leggings") || lower.contains("pants")
                || lower.contains("trousers") || lower.contains("boots")
                || lower.contains("shoes") || lower.contains("elytra")
                || lower.contains("wings");
        if (!armorishSuffix) return Optional.empty();

        int lastUnderscore = filenameStem.lastIndexOf('_');
        if (lastUnderscore > 0) {
            String materialStem = filenameStem.substring(0, lastUnderscore);
            Optional<JsonObject> materialEquip = resolver.getEquipment(ns + ":" + materialStem);
            return materialEquip.flatMap(j -> EquipmentSlotMapper.inferSlot(j, filenameStem));
        }

        return Optional.empty();
    }

    private static String lastPathSegment(String relPath) {
        int slash = relPath.lastIndexOf('/');
        return slash >= 0 ? relPath.substring(slash + 1) : relPath;
    }
}
