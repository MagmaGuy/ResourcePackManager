package com.magmaguy.resourcepackmanager.bedrock.generic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.bedrock.converter.FmmGeyserMappingBuilder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Produces the final Geyser custom-item v2 mappings file by merging the FMM pipeline's
 * output (a flat list of {@link FmmGeyserMappingBuilder.BoneMapping}, all keyed under
 * {@code minecraft:leather_horse_armor}) with the generic pipeline's
 * {@link MappedItemRegistry} (one entry list per base item).
 *
 * <p>Field order per entry mirrors {@code GeyserBaseDefinition.MAP_CODEC}:
 * {@code type, bedrock_identifier, display_name, predicate, bedrock_options, components, model}.
 *
 * <p>Within each {@code items.<base>} array, entries are sorted by
 * (predicate count asc, bedrock_identifier asc) so unconditional defaults appear LAST
 * &mdash; Rainbow's matcher checks longer predicate chains first, so the entry without a
 * predicate must come last to act as the default fall-through case.
 */
public final class GenericGeyserMappingBuilder {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String FMM_BASE_ITEM = "minecraft:leather_horse_armor";

    /** Armor base items recognised by Rainbow's tagging logic (BedrockItemMapper.java:65-67, 277). */
    private static final Set<String> ARMOR_BASE_ITEMS = Set.of(
            "minecraft:leather_helmet", "minecraft:chainmail_helmet", "minecraft:iron_helmet",
            "minecraft:golden_helmet", "minecraft:diamond_helmet", "minecraft:netherite_helmet",
            "minecraft:turtle_helmet",
            "minecraft:leather_chestplate", "minecraft:chainmail_chestplate", "minecraft:iron_chestplate",
            "minecraft:golden_chestplate", "minecraft:diamond_chestplate", "minecraft:netherite_chestplate",
            "minecraft:elytra",
            "minecraft:leather_leggings", "minecraft:chainmail_leggings", "minecraft:iron_leggings",
            "minecraft:golden_leggings", "minecraft:diamond_leggings", "minecraft:netherite_leggings",
            "minecraft:leather_boots", "minecraft:chainmail_boots", "minecraft:iron_boots",
            "minecraft:golden_boots", "minecraft:diamond_boots", "minecraft:netherite_boots"
    );

    private GenericGeyserMappingBuilder() {}

    /**
     * Writes the merged Geyser mappings file.
     *
     * @param fmmMappings the FMM pipeline's accumulated bone mappings (all under leather_horse_armor)
     * @param registry    the generic pipeline's accumulated entries per base item
     * @param outputFile  destination
     */
    public static void merge(List<FmmGeyserMappingBuilder.BoneMapping> fmmMappings,
                             MappedItemRegistry registry,
                             File outputFile) {
        // Accumulate per-base-item entry lists, sorting base items alphabetically.
        TreeMap<String, List<JsonObject>> byBase = new TreeMap<>();

        // 1. Convert FMM entries (all keyed under FMM_BASE_ITEM).
        List<FmmGeyserMappingBuilder.BoneMapping> sortedFmm = new ArrayList<>(fmmMappings);
        sortedFmm.sort(Comparator.comparing(
                FmmGeyserMappingBuilder.BoneMapping::bedrockIdentifier,
                Comparator.nullsLast(Comparator.naturalOrder())));
        Set<String> seenFmmIds = new HashSet<>();
        List<JsonObject> fmmEntries = new ArrayList<>();
        for (FmmGeyserMappingBuilder.BoneMapping bm : sortedFmm) {
            if (bm.bedrockIdentifier() == null || bm.bedrockIdentifier().isEmpty()) {
                Logger.warn("[BedrockConverter] Skipping FMM mapping with empty bedrock_identifier (model="
                        + bm.itemModelKey() + ")");
                continue;
            }
            if (!seenFmmIds.add(bm.bedrockIdentifier())) {
                Logger.warn("[BedrockConverter] Duplicate FMM bedrock_identifier in mappings: "
                        + bm.bedrockIdentifier());
                continue;
            }
            fmmEntries.add(buildFmmEntry(bm));
        }
        if (!fmmEntries.isEmpty()) {
            byBase.put(FMM_BASE_ITEM, fmmEntries);
        }

        // 2. Convert generic entries — one array per base item.
        for (Map.Entry<String, List<GeyserDefinitionEntry>> e : registry.finalDefinitions().entrySet()) {
            String base = e.getKey();
            List<GeyserDefinitionEntry> entries = new ArrayList<>(e.getValue());
            // Sort by (predicate count asc, bedrock_identifier asc) so unconditional defaults come last.
            entries.sort(Comparator
                    .comparingInt((GeyserDefinitionEntry ge) -> ge.predicates() == null ? 0 : ge.predicates().size())
                    .thenComparing(GeyserDefinitionEntry::bedrockIdentifier));
            List<JsonObject> jsons = new ArrayList<>();
            for (GeyserDefinitionEntry ge : entries) {
                jsons.add(buildGenericEntry(ge, base));
            }
            // Merge into existing list (in case FMM and generic share a base item — currently
            // they never do, but the orchestrator is free to evolve).
            byBase.computeIfAbsent(base, k -> new ArrayList<>()).addAll(jsons);
        }

        // 3. Build root JSON.
        JsonObject items = new JsonObject();
        for (Map.Entry<String, List<JsonObject>> e : byBase.entrySet()) {
            JsonArray arr = new JsonArray();
            for (JsonObject entry : e.getValue()) arr.add(entry);
            items.add(e.getKey(), arr);
        }

        JsonObject root = new JsonObject();
        root.addProperty("format_version", 2);
        root.add("items", items);

        // 4. Write atomically via temp-then-rename. Same rationale as BedrockZip:
        // a reader (Geyser at boot, or the GeyserDeployer file-copy) must never see
        // a half-written JSON. Writes go to <file>.tmp, atomic-move to <file> on
        // success.
        int total = byBase.values().stream().mapToInt(List::size).sum();
        int baseCount = byBase.size();
        File tmpFile = new File(outputFile.getParentFile(), outputFile.getName() + ".tmp");
        try {
            if (outputFile.getParentFile() != null) {
                Files.createDirectories(outputFile.getParentFile().toPath());
            }
            try (FileWriter w = new FileWriter(tmpFile, StandardCharsets.UTF_8)) {
                GSON.toJson(root, w);
            }
            try {
                Files.move(tmpFile.toPath(), outputFile.toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicMoveFailed) {
                // Fall back to non-atomic on filesystems that reject ATOMIC_MOVE.
                Files.move(tmpFile.toPath(), outputFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            Logger.info("[BedrockConverter] Wrote merged Geyser mappings: "
                    + total + " entries across " + baseCount + " base items -> "
                    + outputFile.getAbsolutePath());
        } catch (IOException e) {
            Logger.warn("[BedrockConverter] Failed to write Geyser mappings: " + e.getMessage());
            try { Files.deleteIfExists(tmpFile.toPath()); } catch (IOException ignored) {}
        }
    }

    private static JsonObject buildFmmEntry(FmmGeyserMappingBuilder.BoneMapping bm) {
        JsonObject def = new JsonObject();
        def.addProperty("type", "definition");
        def.addProperty("bedrock_identifier", bm.bedrockIdentifier());
        if (bm.displayName() != null && !bm.displayName().isEmpty()) {
            def.addProperty("display_name", bm.displayName());
        }
        // FMM entries have no predicate or components.
        JsonObject options = new JsonObject();
        if (bm.iconKey() != null && !bm.iconKey().isEmpty()) {
            options.addProperty("icon", bm.iconKey());
        }
        if (options.size() > 0) {
            def.add("bedrock_options", options);
        }
        if (bm.itemModelKey() != null && !bm.itemModelKey().isEmpty()) {
            def.addProperty("model", bm.itemModelKey());
        }
        return def;
    }

    private static JsonObject buildGenericEntry(GeyserDefinitionEntry entry, String baseItem) {
        JsonObject def = new JsonObject();
        def.addProperty("type", "definition");
        def.addProperty("bedrock_identifier", entry.bedrockIdentifier());
        // No display_name on generic entries by default — the Java item itself carries a
        // display name via NBT at runtime; the Geyser mapping shouldn't pin one.

        // Predicate (omit if empty; emit as array even for a single entry — Rainbow uses
        // the array form consistently).
        if (entry.predicates() != null && !entry.predicates().isEmpty()) {
            JsonArray preds = new JsonArray();
            for (PredicateRecord p : entry.predicates()) preds.add(p.toGeyserJson());
            def.add("predicate", preds);
        }

        // bedrock_options.
        JsonObject options = new JsonObject();
        if (entry.iconKey() != null && !entry.iconKey().isEmpty()) {
            options.addProperty("icon", entry.iconKey());
        }
        if (entry.handheld()) {
            options.addProperty("display_handheld", true);
        }
        if (ARMOR_BASE_ITEMS.contains(baseItem)) {
            JsonArray tags = new JsonArray();
            tags.add("minecraft:is_armor");
            tags.add("minecraft:trimmable_armors");
            options.add("tags", tags);
        }
        // protection_value: omitted — RSPM has no access to live item attributes.
        // allow_offhand: omitted — Bedrock default is true.
        if (options.size() > 0) {
            def.add("bedrock_options", options);
        }

        // model last (Rainbow order).
        if (entry.javaItemModel() != null && !entry.javaItemModel().isEmpty()) {
            def.addProperty("model", entry.javaItemModel());
        }
        return def;
    }
}
