package com.magmaguy.resourcepackmanager.bedrock.generic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.magmaguy.resourcepackmanager.bedrock.BedrockLog;

import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Scans a merged Java resource pack directory for 1.21.4+ items definition files
 * across every namespace, including {@code minecraft}. Modern vanilla item overrides
 * such as {@code assets/minecraft/items/carrot_on_a_stick.json} are where many packs
 * define their custom-model-data branches.
 * All plugin namespaces are processed uniformly; there is no FMM-specific carve-out.
 *
 * <p>This is the entry point of the generic Java→Bedrock pipeline. Subsequent phases
 * (model walker, base-item resolver, geometry/attachable emission) consume the
 * {@link ItemsDefinition} list produced here.
 *
 * <p>Phase 1 scope: discovery only. Files are parsed into {@link ItemsDefinition}
 * records but no conversion is performed.
 */
public final class GenericJavaScanner {

    private GenericJavaScanner() {}

    /**
     * Walks the merged pack's {@code assets/} tree and returns every parseable items
     * definition file (1.21.4+ format).
     */
    public static List<ItemsDefinition> scan(File mergedJavaPack) {
        List<ItemsDefinition> result = new ArrayList<>();
        File assetsDir = new File(mergedJavaPack, "assets");
        if (!assetsDir.isDirectory()) return result;

        File[] namespaceDirs = assetsDir.listFiles(File::isDirectory);
        if (namespaceDirs == null) return result;

        for (File nsDir : namespaceDirs) {
            String namespace = nsDir.getName();
            File itemsDir = new File(nsDir, "items");
            if (!itemsDir.isDirectory()) continue;
            scanItemsDir(namespace, itemsDir, "", result);
        }

        int modernCount = result.size();
        scanLegacyCustomModelOverrides(assetsDir, result);

        // Per-mix scanner status — useful when debugging "why isn't my pack converting"
        // but pure noise on a clean run (the per-cycle conversion summary already says
        // how many mappings were emitted). Demoted to debug.
        BedrockLog.debug("[BedrockConverter] Generic scanner: discovered " + result.size()
                + " item definitions (" + modernCount + " modern, "
                + (result.size() - modernCount) + " legacy overrides) across "
                + namespaceDirs.length + " namespace(s).");
        return result;
    }

    private static void scanItemsDir(String namespace, File dir, String relPath, List<ItemsDefinition> out) {
        File[] entries = dir.listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            if (entry.isDirectory()) {
                String childRel = relPath.isEmpty() ? entry.getName() : relPath + "/" + entry.getName();
                scanItemsDir(namespace, entry, childRel, out);
            } else if (entry.isFile() && entry.getName().endsWith(".json")) {
                String stem = entry.getName().substring(0, entry.getName().length() - ".json".length());
                String fullRel = relPath.isEmpty() ? stem : relPath + "/" + stem;
                try (FileReader reader = new FileReader(entry, StandardCharsets.UTF_8)) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    out.add(new ItemsDefinition(namespace, fullRel, entry, root));
                } catch (Exception e) {
                    BedrockLog.warn("[BedrockConverter] Failed to parse items definition "
                            + entry.getPath() + ": " + e.getMessage());
                }
            }
        }
    }

    /**
     * ItemsAdder and older packs can still expose custom items through legacy
     * {@code assets/minecraft/models/item/<base>.json} overrides. Those files declare
     * both the Java base item and the custom model data threshold, so synthesize a
     * modern range_dispatch-shaped definition and let the normal converter handle it.
     */
    private static void scanLegacyCustomModelOverrides(File assetsDir, List<ItemsDefinition> out) {
        File legacyItemsDir = new File(assetsDir, "minecraft/models/item");
        if (!legacyItemsDir.isDirectory()) return;

        File[] files = legacyItemsDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return;

        for (File file : files) {
            String baseStem = file.getName().substring(0, file.getName().length() - ".json".length());
            if (baseStem.contains("/") || baseStem.contains("\\")) continue;
            String baseItem = "minecraft:" + baseStem;

            try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (!parsed.isJsonObject()) continue;
                JsonObject root = parsed.getAsJsonObject();
                if (!root.has("overrides") || !root.get("overrides").isJsonArray()) continue;
                scanLegacyOverridesFile(file, baseItem, root.getAsJsonArray("overrides"), out);
            } catch (Exception e) {
                BedrockLog.warn("[BedrockConverter] Failed to parse legacy item model overrides "
                        + file.getPath() + ": " + e.getMessage());
            }
        }
    }

    private static void scanLegacyOverridesFile(File file, String baseItem, JsonArray overrides,
                                                List<ItemsDefinition> out) {
        for (JsonElement overrideElement : overrides) {
            if (!overrideElement.isJsonObject()) continue;
            JsonObject override = overrideElement.getAsJsonObject();
            if (!override.has("model") || !override.get("model").isJsonPrimitive()) continue;
            if (!override.has("predicate") || !override.get("predicate").isJsonObject()) continue;

            JsonElement customModelData = override.getAsJsonObject("predicate").get("custom_model_data");
            if (!isNumericPrimitive(customModelData)) continue;

            String modelRef = normalizeReference(override.get("model").getAsString());
            ItemsDefinition def = synthesizeLegacyDefinition(file, baseItem, modelRef, customModelData);
            if (def != null) out.add(def);
        }
    }

    private static ItemsDefinition synthesizeLegacyDefinition(File file, String baseItem,
                                                              String modelRef, JsonElement customModelData) {
        int colon = modelRef.indexOf(':');
        String namespace = colon > 0 ? modelRef.substring(0, colon) : "minecraft";
        String itemsRelPath = colon > 0 ? modelRef.substring(colon + 1) : modelRef;
        if (itemsRelPath.isBlank()) return null;

        JsonObject leaf = new JsonObject();
        leaf.addProperty("type", "minecraft:model");
        leaf.addProperty("model", modelRef);

        JsonObject entry = new JsonObject();
        entry.add("threshold", customModelData.deepCopy());
        entry.add("model", leaf);

        JsonArray entries = new JsonArray();
        entries.add(entry);

        JsonObject dispatch = new JsonObject();
        dispatch.addProperty("type", "minecraft:range_dispatch");
        dispatch.addProperty("property", "minecraft:custom_model_data");
        dispatch.addProperty("scale", 1.0);
        dispatch.add("entries", entries);

        JsonObject root = new JsonObject();
        root.add("model", dispatch);
        return new ItemsDefinition(namespace, itemsRelPath, file, root, List.of(baseItem));
    }

    private static boolean isNumericPrimitive(JsonElement element) {
        return element != null
                && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isNumber();
    }

    private static String normalizeReference(String reference) {
        return reference.contains(":") ? reference : "minecraft:" + reference;
    }
}
