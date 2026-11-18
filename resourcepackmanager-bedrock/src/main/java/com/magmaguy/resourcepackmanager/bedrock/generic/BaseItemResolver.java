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
import java.util.Map;
import java.util.Optional;

/**
 * Resolves which vanilla base Java items a generic plugin's custom item_model should be
 * registered under in the Geyser mappings file. See Option J plan, "Equipment-aware armor
 * detection" and "Filename heuristic table".
 *
 * <p>Resolution order: filename heuristic for clearly-weapon/tool patterns →
 * equipment-file lookup (armor case) → broader filename heuristic → generic fallback.
 * Base items the pack itself puts the models on ({@link PackCarrierIndex}) skip all four.
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

    /** FreeMinecraftModels' {@code /fmm craftify} hands every display model out on paper. */
    private static final String FMM_CRAFTIFY_CARRIER = "minecraft:paper";

    /**
     * How a model's declared base items (an {@code rspm_item_bases} file, an item plugin's
     * material, a data pack's item stacks) combine with the bases guessed from its file name.
     * {@code true}: a model with at least one declared base maps only under its declared bases.
     * {@code false}: the declared bases are added to the guessed ones, as up to 2.4.12.
     *
     * <p>Every guessed base is one more Bedrock custom item, and Geyser only uses it when a
     * stack on that base item carries the model. One server's pack made 5137 Bedrock items
     * with the guesses added and 808 without them (support ticket "Converting issue",
     * 2026-11-18). A model nothing declares keeps its guesses either way.
     */
    public static final boolean DECLARED_BASES_REPLACE_GUESSES = true;

    /** The one place the {@link #DECLARED_BASES_REPLACE_GUESSES} rule is applied. */
    private static boolean replacesGuesses(List<String> declared) {
        return DECLARED_BASES_REPLACE_GUESSES && !declared.isEmpty();
    }

    /**
     * Resolves the candidate base-item list for a given items definition.
     * The heuristic itself always produces at least one candidate, but the final
     * result can be empty when every candidate is unsafe to register as a Geyser
     * custom-item override.
     *
     * <p>{@code pluginDeclaredBaseItems} are the base items an installed plugin's config or a
     * data pack assigns to this item model (keyed by {@link ItemsDefinition#itemIdentifier()}).
     * Oraxen items carry only {@code oraxen:<id>} in the pack, so without their
     * {@code material:} a tomato on apples would map under the paper/stick fallback and Bedrock
     * would show an apple. See {@link #DECLARED_BASES_REPLACE_GUESSES} for how they combine
     * with the guesses.
     *
     * <p>{@code packCarriers} are the base items the pack
     * itself puts this definition's models on ({@link PackCarrierIndex#carriersFor}). When
     * present they replace the filename heuristic and the generic fallback: the producer has
     * named its carrier, and every guessed base is one more Bedrock custom item. Without this,
     * each ModelEngine bone mapped under about twelve base items although ModelEngine only
     * ever shows bones on leather horse armor.
     */
    public static List<String> resolve(ItemsDefinition def, AssetResolver resolver,
                                       Map<String, List<String>> pluginDeclaredBaseItems,
                                       List<String> packCarriers) {
        List<String> candidates;
        if (def.hasExplicitBaseItems()) {
            candidates = def.explicitBaseItems();
        } else if (def.isRootVanillaItem()) {
            // assets/minecraft/items/<item>.json names the actual Java base item. Using
            // the filename heuristic here would misroute carrot_on_a_stick to minecraft:stick.
            candidates = List.of(def.itemIdentifier());
        } else {
            // The producer knows the real carrier (a wand YAML says BLAZE_ROD, a data pack
            // recipe says flint_and_steel); the filename heuristic would only guess.
            List<String> declared = new ArrayList<>(declaredBaseItems(def));
            for (String base : pluginDeclaredBaseItems.getOrDefault(def.itemIdentifier(), List.of()))
                if (base.matches("minecraft:[a-z0-9_]+") && !declared.contains(base)) declared.add(base);

            boolean packCarried = packCarriers != null && !packCarriers.isEmpty();
            boolean freeMinecraftModels = "freeminecraftmodels".equals(def.namespace());
            // Fresh list: the heuristic's and the carrier index's lists are shared.
            List<String> merged = new ArrayList<>();
            if (packCarried) {
                merged.addAll(packCarriers);
            } else if (!replacesGuesses(declared)) {
                merged.addAll(resolveHeuristic(def, resolver));
            } else if (freeMinecraftModels) {
                merged.add(FMM_CRAFTIFY_CARRIER);
            }

            // FreeMinecraftModels always wears items on a leather_horse_armor carrier when
            // rendering bones via packet armor stands. Ensure the FMM model is registered
            // under that base item too, in addition to whatever else carries it. Without
            // this the bone armor stand renders as plain leather-horse-armor on Bedrock and
            // the user-visible FMM furniture / props / weapons are invisible.
            if (freeMinecraftModels && !merged.contains(FMM_BONE_CARRIER)) merged.add(FMM_BONE_CARRIER);

            for (String base : declared) if (!merged.contains(base)) merged.add(base);
            candidates = merged;
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
     * custom item with the material its YAML names. {@link #DECLARED_BASES_REPLACE_GUESSES}
     * says how they combine with the heuristic's choice.
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
