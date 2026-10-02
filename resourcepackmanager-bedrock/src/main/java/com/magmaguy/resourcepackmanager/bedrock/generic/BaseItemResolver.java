package com.magmaguy.resourcepackmanager.bedrock.generic;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.magmaguy.resourcepackmanager.bedrock.BedrockLog;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
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
     * The heuristic itself always produces at least one candidate, but the final
     * result can be empty when every candidate is unsafe to register as a Geyser
     * custom-item override.
     */
    public static List<String> resolve(ItemsDefinition def, AssetResolver resolver) {
        List<String> candidates;
        if (def.hasExplicitBaseItems()) {
            candidates = def.explicitBaseItems();
        } else if (isRootVanillaItemDefinition(def)) {
            candidates = List.of(def.itemIdentifier());
        } else {
            candidates = resolveHeuristic(def, resolver);

            // FreeMinecraftModels always wears items on a leather_horse_armor carrier when
            // rendering bones via packet armor stands. Ensure the FMM model is registered
            // under that base item too, in addition to whatever the filename heuristic
            // picked (which covers the /craftify or held-item case). Without this the
            // bone armor stand renders as plain leather-horse-armor on Bedrock and the
            // user-visible FMM furniture / props / weapons are invisible.
            if ("freeminecraftmodels".equals(def.namespace())
                    && !candidates.contains(FMM_BONE_CARRIER)) {
                // Build a fresh list so we don't mutate the (often-shared)
                // heuristic-returned list.
                List<String> merged = new ArrayList<>(candidates.size() + 1);
                merged.addAll(candidates);
                merged.add(FMM_BONE_CARRIER);
                candidates = merged;
            }

            // The producer knows the real carrier (a wand YAML says BLAZE_ROD); the
            // filename heuristic would only guess minecraft:stick from "wand".
            List<String> declared = declaredBaseItems(def);
            if (!declared.isEmpty()) {
                List<String> merged = new ArrayList<>(candidates);
                for (String base : declared) if (!merged.contains(base)) merged.add(base);
                candidates = merged;
            }
        }

        List<String> supported = candidates.stream()
                .filter(GeyserBaseItemCompatibility::supportsCustomItemOverride)
                .toList();
        if (supported.size() != candidates.size()) {
            List<String> rejected = candidates.stream()
                    .filter(base -> !GeyserBaseItemCompatibility.supportsCustomItemOverride(base))
                    .toList();
            BedrockLog.warn("[BedrockConverter] Skipping Geyser custom-item mapping for "
                    + def.itemIdentifier() + " on incompatible block-item base(s): "
                    + String.join(", ", rejected)
                    + ". Bedrock will use the vanilla icon until Geyser can override "
                    + "these item/block identifier mismatches safely.");
        }
        return supported;
    }

    /**
     * Base items a producer declares for one of its item models, read from
     * {@code assets/<ns>/rspm_item_bases/<items path>.json} holding
     * {@code {"base_items": ["minecraft:blaze_rod"]}}. FreeMinecraftModels writes one for each
     * custom item with the material its YAML names. These are added to the heuristic's choice,
     * so other carriers of the same model keep working.
     */
    static List<String> declaredBaseItems(ItemsDefinition def) {
        File itemsDir = def.file() == null ? null : def.file().getParentFile();
        String relPath = def.itemsRelPath().replace('\\', '/');
        if (relPath.contains("..")) return List.of();
        for (int depth = relPath.split("/").length - 1; depth > 0 && itemsDir != null; depth--)
            itemsDir = itemsDir.getParentFile();
        if (itemsDir == null || !"items".equals(itemsDir.getName()) || itemsDir.getParentFile() == null)
            return List.of();
        File sidecar = new File(itemsDir.getParentFile(), "rspm_item_bases/" + relPath + ".json");
        if (!sidecar.isFile()) return List.of();
        try {
            JsonObject json = JsonParser.parseString(Files.readString(sidecar.toPath(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            List<String> bases = new ArrayList<>();
            for (JsonElement element : json.getAsJsonArray("base_items")) {
                String base = element.getAsString();
                if (base.matches("minecraft:[a-z0-9_]+")) bases.add(base);
            }
            return List.copyOf(bases);
        } catch (IOException | RuntimeException exception) {
            BedrockLog.warn("[BedrockConverter] Ignoring unreadable base item declaration " + sidecar + ": "
                    + exception.getMessage());
            return List.of();
        }
    }

    private static boolean isRootVanillaItemDefinition(ItemsDefinition def) {
        // assets/minecraft/items/<item>.json names the actual Java base item. Using
        // the filename heuristic here would misroute carrot_on_a_stick to minecraft:stick.
        return "minecraft".equals(def.namespace())
                && !def.itemsRelPath().contains("/")
                && !def.itemsRelPath().contains("\\");
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
