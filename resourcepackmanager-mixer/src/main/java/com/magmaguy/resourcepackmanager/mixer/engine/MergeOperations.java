package com.magmaguy.resourcepackmanager.mixer.engine;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MergeOperations {
    private final MixerLogger logger;

    public MergeOperations(MixerLogger logger) {
        this.logger = logger;
    }

    public JsonObject readJsonFile(File file) {
        try (FileReader reader = new FileReader(file)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            logger.warn("Malformed JSON: " + file.getAbsolutePath());
            try (FileReader reader = new FileReader(file);
                 JsonReader jsonReader = new JsonReader(reader)) {
                jsonReader.setStrictness(Strictness.LENIENT);
                return JsonParser.parseReader(jsonReader).getAsJsonObject();
            } catch (Exception ex) {
                logger.warn("Unreadable JSON: " + file.getAbsolutePath());
                return null;
            }
        }
    }

    /**
     * Checks if a JSON file is designed to be merged (content can be combined).
     * Files like sounds.json, lang files, atlases, fonts, and vanilla item model overrides can be merged.
     * Custom model files, blockstates, equipment layers, etc. have fixed-size arrays that break when concatenated.
     */
    public boolean isMergeableJsonFile(File file) {
        String path = file.getPath().replace("\\", "/");
        String fileName = file.getName();

        // sounds.json files should be merged
        if (fileName.equals("sounds.json")) {
            return true;
        }

        // Language files should be merged
        if (path.contains("/lang/") || path.contains("/languages/")) {
            return true;
        }

        // Vanilla item model files (models/item/*.json) are NOT mergeable in 1.21.4+.
        // They contain fixed-length numeric arrays in `display.<context>.translation/rotation/scale`
        // and atomic `textures` references, which our recursive merge corrupts (translation
        // [10,6,-4] + [0,0,0] -> [10,6,-4,0,0,0] for shield, and textures get wedged for
        // crossbow leading to the missing-texture purple/black). The original justification
        // for merging these was to combine `overrides` arrays for custom_model_data — but
        // `overrides` was removed in 24w45a / 1.21.4+, replaced by assets/<ns>/items/*.json
        // dispatch. So the merge benefit is gone, the corruption risk remains; higher-priority
        // pack wins atomically, like blockstates.

        // Atlas files should be merged (sources array)
        if (path.contains("/atlases/")) {
            return true;
        }

        // Font files should be merged (providers array)
        if (path.contains("/font/")) {
            return true;
        }

        // 1.21.4+ item model definitions should be merged (range_dispatch entries, select cases)
        if (path.contains("/items/")) {
            return true;
        }

        // All other JSON files (custom models, blockstates, equipment layers, etc.) should not be merged
        return false;
    }

    public JsonObject mergeJsonObjects(JsonObject json1, JsonObject json2) {
        JsonObject mergedJson = new JsonObject();

        for (String key : json1.keySet()) {
            if (json2.has(key)) {
                JsonElement value1 = json1.get(key);
                JsonElement value2 = json2.get(key);
                if (value1.isJsonObject() && value2.isJsonObject()) {
                    mergedJson.add(key, mergeJsonObjects(value1.getAsJsonObject(), value2.getAsJsonObject()));
                } else if (value1.isJsonArray() && value2.isJsonArray()) {
                    mergedJson.add(key, mergeJsonArrays(value1.getAsJsonArray(), value2.getAsJsonArray()));
                } else {
                    mergedJson.add(key, value2); // Override with the value from json2
                }
            } else {
                mergedJson.add(key, json1.get(key));
            }
        }

        for (String key : json2.keySet()) {
            if (!json1.has(key)) {
                mergedJson.add(key, json2.get(key));
            }
        }

        return mergedJson;
    }

    private JsonArray mergeJsonArrays(JsonArray array1, JsonArray array2) {
        JsonArray mergedArray = new JsonArray();

        for (JsonElement element : array1) {
            mergedArray.add(element);
        }

        for (JsonElement element : array2) {
            mergedArray.add(element);
        }

        return mergedArray;
    }

    /**
     * After all packs are merged, overlay directories may contain atlas files that shadow the base atlas.
     * When Minecraft activates an overlay, its atlas file replaces the base — so any sources defined only
     * in the base are lost. This method copies base atlas sources into each overlay atlas file to prevent that.
     */
    public void mergeBaseAtlasSourcesIntoOverlays(File resourcePackRoot) {
        File packMcmeta = new File(resourcePackRoot, "pack.mcmeta");
        if (!packMcmeta.exists()) return;

        JsonObject mcmeta = readJsonFile(packMcmeta);
        if (mcmeta == null || !mcmeta.has("overlays")) return;

        JsonObject overlays = mcmeta.getAsJsonObject("overlays");
        if (!overlays.has("entries")) return;

        for (JsonElement entry : overlays.getAsJsonArray("entries")) {
            if (!entry.isJsonObject()) continue;
            JsonObject overlayEntry = entry.getAsJsonObject();
            if (!overlayEntry.has("directory")) continue;
            String overlayDir = overlayEntry.get("directory").getAsString();

            File overlayRoot = new File(resourcePackRoot, overlayDir);
            if (!overlayRoot.exists() || !overlayRoot.isDirectory()) continue;

            mergeBaseAtlasesForOverlay(resourcePackRoot, overlayRoot);
        }
    }

    private void mergeBaseAtlasesForOverlay(File resourcePackRoot, File overlayRoot) {
        File overlayAssets = new File(overlayRoot, "assets");
        if (!overlayAssets.exists() || !overlayAssets.isDirectory()) return;

        File[] namespaces = overlayAssets.listFiles(File::isDirectory);
        if (namespaces == null) return;

        for (File namespace : namespaces) {
            File atlasesDir = new File(namespace, "atlases");
            if (!atlasesDir.exists() || !atlasesDir.isDirectory()) continue;

            File[] atlasFiles = atlasesDir.listFiles((dir, name) -> name.endsWith(".json"));
            if (atlasFiles == null) continue;

            for (File overlayAtlas : atlasFiles) {
                String relativePath = "assets" + File.separatorChar + namespace.getName()
                        + File.separatorChar + "atlases" + File.separatorChar + overlayAtlas.getName();
                File baseAtlas = new File(resourcePackRoot, relativePath);
                if (!baseAtlas.exists()) continue;
                mergeBaseSourcesIntoOverlayAtlas(baseAtlas, overlayAtlas);
            }
        }
    }

    private void mergeBaseSourcesIntoOverlayAtlas(File baseAtlas, File overlayAtlas) {
        JsonObject baseJson = readJsonFile(baseAtlas);
        JsonObject overlayJson = readJsonFile(overlayAtlas);

        if (baseJson == null || overlayJson == null) return;
        if (!baseJson.has("sources") || !overlayJson.has("sources")) return;

        JsonArray baseSources = baseJson.getAsJsonArray("sources");
        JsonArray overlaySources = overlayJson.getAsJsonArray("sources");

        Set<String> existingSignatures = new HashSet<>();
        for (JsonElement e : overlaySources) {
            existingSignatures.add(e.toString());
        }

        JsonArray merged = new JsonArray();
        int addedCount = 0;
        for (JsonElement baseSource : baseSources) {
            if (!existingSignatures.contains(baseSource.toString())) {
                merged.add(baseSource);
                addedCount++;
            }
        }
        for (JsonElement overlaySource : overlaySources) {
            merged.add(overlaySource);
        }

        if (addedCount == 0) return;

        overlayJson.add("sources", merged);

        try (FileWriter writer = new FileWriter(overlayAtlas)) {
            new Gson().toJson(overlayJson, writer);
        } catch (IOException e) {
            logger.warn("Failed to merge base atlas sources into overlay atlas: " + overlayAtlas.getPath());
        }

        logger.collision("Merged base atlas sources into overlay: " + overlayAtlas.getPath()
                + " (" + addedCount + " sources added from base)");
    }

    public void mergePackMcmeta(File sourceFile, File targetFile) throws IOException {
        JsonObject source = readJsonFile(sourceFile);
        JsonObject target = readJsonFile(targetFile);

        if (source == null) return;
        if (target == null) {
            Files.copy(sourceFile.toPath(), targetFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return;
        }

        // Take the highest pack_format
        if (source.has("pack") && target.has("pack")) {
            JsonObject sourcePack = source.getAsJsonObject("pack");
            JsonObject targetPack = target.getAsJsonObject("pack");
            if (sourcePack.has("pack_format") && targetPack.has("pack_format")) {
                int sourceFormat = sourcePack.get("pack_format").getAsInt();
                int targetFormat = targetPack.get("pack_format").getAsInt();
                targetPack.addProperty("pack_format", Math.max(sourceFormat, targetFormat));
            }
            // 1.21.9+: widen pack.min_format and pack.max_format to cover both packs' supported range.
            // These supersede the older pack.supported_formats. Either side can declare them; missing
            // values default to pack_format so a one-sided declaration still widens correctly.
            int sourceMin = readFormatRangeMin(sourcePack);
            int sourceMax = readFormatRangeMax(sourcePack);
            int targetMin = readFormatRangeMin(targetPack);
            int targetMax = readFormatRangeMax(targetPack);
            if (sourcePack.has("min_format") || targetPack.has("min_format")
                    || sourcePack.has("max_format") || targetPack.has("max_format")) {
                if (sourceMin != Integer.MAX_VALUE || targetMin != Integer.MAX_VALUE) {
                    int min = Math.min(
                            sourceMin == Integer.MAX_VALUE ? targetMin : sourceMin,
                            targetMin == Integer.MAX_VALUE ? sourceMin : targetMin);
                    targetPack.addProperty("min_format", min);
                }
                if (sourceMax != Integer.MIN_VALUE || targetMax != Integer.MIN_VALUE) {
                    int max = Math.max(
                            sourceMax == Integer.MIN_VALUE ? targetMax : sourceMax,
                            targetMax == Integer.MIN_VALUE ? sourceMax : targetMax);
                    targetPack.addProperty("max_format", max);
                }
            }
        }

        // Merge supported_formats to widest range.
        // Per minecraft.wiki/w/Pack.mcmeta, supported_formats can be: a 2-int array [min, max],
        // a single int (a single format version), or an object {min_inclusive, max_inclusive}.
        // We parse whichever shape each side ships and emit the widened result as a 2-int array.
        // (Deprecated/removed in 1.21.9+ in favour of pack.min_format/max_format above, but
        // still seen on older packs in the wild.)
        if (source.has("supported_formats") || target.has("supported_formats")) {
            int[] sourceRange = parseSupportedFormatsRange(source.get("supported_formats"));
            int[] targetRange = parseSupportedFormatsRange(target.get("supported_formats"));
            if (sourceRange != null && targetRange != null) {
                JsonArray merged = new JsonArray();
                merged.add(Math.min(sourceRange[0], targetRange[0]));
                merged.add(Math.max(sourceRange[1], targetRange[1]));
                target.add("supported_formats", merged);
            } else if (sourceRange != null) {
                JsonArray merged = new JsonArray();
                merged.add(sourceRange[0]);
                merged.add(sourceRange[1]);
                target.add("supported_formats", merged);
            }
            // else: only target had a parseable value → keep target's untouched.
        }

        // Merge overlay entries from both packs
        JsonArray mergedEntries = new JsonArray();

        if (target.has("overlays")) {
            JsonObject targetOverlays = target.getAsJsonObject("overlays");
            if (targetOverlays.has("entries")) {
                mergedEntries.addAll(targetOverlays.getAsJsonArray("entries"));
            }
        }
        if (source.has("overlays")) {
            JsonObject sourceOverlays = source.getAsJsonObject("overlays");
            if (sourceOverlays.has("entries")) {
                Set<String> existingDirs = new HashSet<>();
                for (JsonElement e : mergedEntries) {
                    if (e.isJsonObject() && e.getAsJsonObject().has("directory")) {
                        existingDirs.add(e.getAsJsonObject().get("directory").getAsString());
                    }
                }
                for (JsonElement e : sourceOverlays.getAsJsonArray("entries")) {
                    if (e.isJsonObject()) {
                        String dir = e.getAsJsonObject().has("directory")
                                ? e.getAsJsonObject().get("directory").getAsString() : "";
                        if (!existingDirs.contains(dir)) {
                            mergedEntries.add(e);
                        }
                    }
                }
            }
        }

        if (mergedEntries.size() > 0) {
            // Defensive normalization: ensure overlay entries have min_format/max_format fields.
            // Starting with resource pack format 65 (Minecraft 1.21.9+), overlay entries MUST include
            // min_format and max_format as separate fields — the old "formats" field alone is no longer
            // sufficient. If an overlay's format range covers 65+, the client rejects entries missing
            // these fields with: "declares support for version newer than 64, but is missing mandatory
            // fields min_format and max_format".
            // This is NOT an RSPM bug — the source packs (e.g. ModelEngine) are generating overlay entries
            // without these fields. Ideally those packs should fix their own pack.mcmeta output.
            // We patch it here because RSPM gets the bug reports when the merged pack fails to load.
            normalizeOverlayEntries(mergedEntries);

            JsonObject overlays = new JsonObject();
            overlays.add("entries", mergedEntries);
            target.add("overlays", overlays);
        }

        // Preserve any non-standard top-level keys from source (e.g. "sodium" with ignored_shaders)
        for (String key : source.keySet()) {
            if (!target.has(key)) {
                target.add(key, source.get(key));
            }
        }

        try (FileWriter writer = new FileWriter(targetFile)) {
            new Gson().toJson(target, writer);
        }

        logger.collision("Merged pack.mcmeta: " + targetFile.getPath());
    }

    /**
     * Read pack.min_format from a `pack` block. Accepts int form or 2-int array form
     * (some pre-1.21.9 packs shipped min_format as the same shape as supported_formats).
     * Returns Integer.MAX_VALUE if the field is missing or unreadable so callers can
     * detect "not declared" and skip widening.
     */
    private int readFormatRangeMin(JsonObject pack) {
        if (!pack.has("min_format")) return Integer.MAX_VALUE;
        JsonElement el = pack.get("min_format");
        if (el.isJsonPrimitive()) return el.getAsInt();
        if (el.isJsonArray() && el.getAsJsonArray().size() >= 1) return el.getAsJsonArray().get(0).getAsInt();
        return Integer.MAX_VALUE;
    }

    private int readFormatRangeMax(JsonObject pack) {
        if (!pack.has("max_format")) return Integer.MIN_VALUE;
        JsonElement el = pack.get("max_format");
        if (el.isJsonPrimitive()) return el.getAsInt();
        if (el.isJsonArray() && el.getAsJsonArray().size() >= 1) {
            JsonArray arr = el.getAsJsonArray();
            return arr.get(arr.size() - 1).getAsInt();
        }
        return Integer.MIN_VALUE;
    }

    /**
     * Parse `supported_formats` in any of its three documented shapes (int, 2-int array,
     * {min_inclusive, max_inclusive} object) into a [min, max] pair. Returns null when
     * the field is missing or malformed so the caller can fall back to "use the other
     * side's value" instead of corrupting the field.
     */
    private int[] parseSupportedFormatsRange(JsonElement el) {
        if (el == null) return null;
        if (el.isJsonPrimitive()) {
            int v = el.getAsInt();
            return new int[]{v, v};
        }
        if (el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            if (arr.size() >= 2) return new int[]{arr.get(0).getAsInt(), arr.get(1).getAsInt()};
            return null;
        }
        if (el.isJsonObject()) {
            JsonObject obj = el.getAsJsonObject();
            if (!obj.has("min_inclusive") || !obj.has("max_inclusive")) return null;
            return new int[]{obj.get("min_inclusive").getAsInt(), obj.get("max_inclusive").getAsInt()};
        }
        return null;
    }

    /**
     * Patches overlay entries that are missing min_format/max_format fields.
     * See comment at call site for full rationale — this works around third-party packs
     * that haven't updated their pack.mcmeta to the 1.21.9+ overlay format.
     */
    private void normalizeOverlayEntries(JsonArray entries) {
        for (JsonElement element : entries) {
            if (!element.isJsonObject()) continue;
            JsonObject entry = element.getAsJsonObject();
            if (entry.has("min_format") && entry.has("max_format")) continue;
            if (!entry.has("formats")) continue;

            int min, max;
            JsonElement formats = entry.get("formats");
            if (formats.isJsonArray()) {
                JsonArray arr = formats.getAsJsonArray();
                if (arr.size() < 2) continue;
                min = arr.get(0).getAsInt();
                max = arr.get(1).getAsInt();
            } else if (formats.isJsonObject()) {
                JsonObject obj = formats.getAsJsonObject();
                if (!obj.has("min_inclusive") || !obj.has("max_inclusive")) continue;
                min = obj.get("min_inclusive").getAsInt();
                max = obj.get("max_inclusive").getAsInt();
            } else if (formats.isJsonPrimitive()) {
                min = max = formats.getAsInt();
            } else {
                continue;
            }

            if (!entry.has("min_format")) entry.addProperty("min_format", min);
            if (!entry.has("max_format")) entry.addProperty("max_format", max);
        }
    }

    public boolean isLegacyItemModel(File file) {
        return file.getPath().replace("\\", "/").contains("/minecraft/models/item/");
    }

    public boolean isItemsFile(File file) {
        String path = file.getPath().replace("\\", "/");
        return path.contains("/items/") && !path.contains("/models/item/");
    }

    public void sortModelOverrides(JsonObject modelJson) {
        JsonArray overrides = modelJson.getAsJsonArray("overrides");
        if (overrides == null || overrides.size() <= 1) return;

        List<JsonElement> sorted = new ArrayList<>();
        for (JsonElement e : overrides) sorted.add(e);

        sorted.sort((a, b) -> {
            int cmdA = getCustomModelData(a);
            int cmdB = getCustomModelData(b);
            return Integer.compare(cmdA, cmdB);
        });

        JsonArray sortedArray = new JsonArray();
        for (JsonElement e : sorted) sortedArray.add(e);
        modelJson.add("overrides", sortedArray);
    }

    private int getCustomModelData(JsonElement override) {
        try {
            return override.getAsJsonObject()
                    .getAsJsonObject("predicate")
                    .get("custom_model_data").getAsInt();
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    public JsonObject mergeItemsModels(JsonObject source, JsonObject target) {
        // Items definitions (assets/<ns>/items/*.json, 1.21.4+) are only safely
        // mergeable when both files have a `model` block of the SAME dispatch
        // type (range_dispatch or select) with the same property. Those have a
        // documented "entries/cases" array semantics that can combine across packs.
        //
        // Every other shape — plain `model`, `composite`, `bundle/selected_item`,
        // `special`, `empty`, or two dispatch types that don't match — is NOT
        // safely mergeable. Falling through to a generic deep-merge would:
        //   - concatenate `transformation.translation/scale/rotation` (fixed-length
        //     numeric arrays) into invalid 6-element arrays,
        //   - concatenate `tints` RGB triplets into double-length nonsense,
        //   - concatenate `composite.models` arrays, stacking layers from the
        //     lower-priority pack on top of the higher one (wrong render order).
        // In all those cases the higher-priority pack wins atomically — the same
        // policy vanilla itself uses for stacked-pack collisions.

        if (!source.has("model") || !target.has("model")) {
            return target;
        }

        JsonObject sourceModel = source.getAsJsonObject("model");
        JsonObject targetModel = target.getAsJsonObject("model");

        String sourceType = sourceModel.has("type") ? sourceModel.get("type").getAsString().replace("minecraft:", "") : "";
        String targetType = targetModel.has("type") ? targetModel.get("type").getAsString().replace("minecraft:", "") : "";

        if (sourceType.equals("range_dispatch") && targetType.equals("range_dispatch")) {
            String sourceProp = sourceModel.has("property") ? sourceModel.get("property").getAsString() : "";
            String targetProp = targetModel.has("property") ? targetModel.get("property").getAsString() : "";
            if (!sourceProp.equals(targetProp)) return target;
            mergeRangeDispatchEntries(sourceModel, targetModel);
            target.add("model", targetModel);
            for (String key : source.keySet()) {
                if (!key.equals("model") && !target.has(key)) {
                    target.add(key, source.get(key));
                }
            }
            return target;
        }

        if (sourceType.equals("select") && targetType.equals("select")) {
            String sourceProp = sourceModel.has("property") ? sourceModel.get("property").getAsString() : "";
            String targetProp = targetModel.has("property") ? targetModel.get("property").getAsString() : "";
            if (sourceProp.equals(targetProp)) {
                mergeSelectCases(sourceModel, targetModel);
                target.add("model", targetModel);
                for (String key : source.keySet()) {
                    if (!key.equals("model") && !target.has(key)) {
                        target.add(key, source.get(key));
                    }
                }
                return target;
            }
        }

        // Incompatible types or non-dispatch model: higher priority (target) wins atomically.
        return target;
    }

    private void mergeRangeDispatchEntries(JsonObject sourceModel, JsonObject targetModel) {
        JsonArray sourceEntries = sourceModel.has("entries") ? sourceModel.getAsJsonArray("entries") : new JsonArray();
        JsonArray targetEntries = targetModel.has("entries") ? targetModel.getAsJsonArray("entries") : new JsonArray();

        // Collect all entries, target (higher priority) wins on threshold conflicts
        Map<Double, JsonElement> entryMap = new LinkedHashMap<>();
        for (JsonElement e : sourceEntries) {
            double threshold = e.getAsJsonObject().has("threshold")
                    ? e.getAsJsonObject().get("threshold").getAsDouble() : 0;
            entryMap.put(threshold, e);
        }
        for (JsonElement e : targetEntries) {
            double threshold = e.getAsJsonObject().has("threshold")
                    ? e.getAsJsonObject().get("threshold").getAsDouble() : 0;
            entryMap.put(threshold, e);
        }

        List<Map.Entry<Double, JsonElement>> sorted = new ArrayList<>(entryMap.entrySet());
        sorted.sort(Comparator.comparingDouble(Map.Entry::getKey));

        JsonArray merged = new JsonArray();
        for (Map.Entry<Double, JsonElement> entry : sorted) {
            merged.add(entry.getValue());
        }

        targetModel.add("entries", merged);
    }

    private void mergeSelectCases(JsonObject sourceModel, JsonObject targetModel) {
        JsonArray sourceCases = sourceModel.has("cases") ? sourceModel.getAsJsonArray("cases") : new JsonArray();
        JsonArray targetCases = targetModel.has("cases") ? targetModel.getAsJsonArray("cases") : new JsonArray();

        Map<String, JsonElement> caseMap = new LinkedHashMap<>();
        for (JsonElement e : sourceCases) {
            String when = e.getAsJsonObject().has("when")
                    ? e.getAsJsonObject().get("when").getAsString() : "";
            caseMap.put(when, e);
        }
        for (JsonElement e : targetCases) {
            String when = e.getAsJsonObject().has("when")
                    ? e.getAsJsonObject().get("when").getAsString() : "";
            caseMap.put(when, e);
        }

        JsonArray merged = new JsonArray();
        for (JsonElement e : caseMap.values()) {
            merged.add(e);
        }

        targetModel.add("cases", merged);
    }

    public JsonObject mergeSoundsJson(JsonObject source, JsonObject target) {
        JsonObject merged = new JsonObject();

        // Start with all source (lower priority) events
        for (String key : source.keySet()) {
            merged.add(key, source.get(key));
        }

        // Apply target (higher priority) events
        for (String key : target.keySet()) {
            JsonElement targetEvent = target.get(key);
            if (!merged.has(key)) {
                merged.add(key, targetEvent);
                continue;
            }

            if (targetEvent.isJsonObject()) {
                JsonObject targetObj = targetEvent.getAsJsonObject();
                boolean replace = targetObj.has("replace") && targetObj.get("replace").getAsBoolean();

                if (replace) {
                    merged.add(key, targetEvent);
                } else {
                    JsonObject sourceObj = merged.get(key).isJsonObject()
                            ? merged.get(key).getAsJsonObject() : new JsonObject();
                    JsonObject mergedEvent = new JsonObject();

                    JsonArray mergedSounds = new JsonArray();
                    if (sourceObj.has("sounds")) {
                        mergedSounds.addAll(sourceObj.getAsJsonArray("sounds"));
                    }
                    if (targetObj.has("sounds")) {
                        mergedSounds.addAll(targetObj.getAsJsonArray("sounds"));
                    }
                    mergedEvent.add("sounds", mergedSounds);

                    for (String prop : sourceObj.keySet()) {
                        if (!prop.equals("sounds") && !prop.equals("replace")) {
                            mergedEvent.add(prop, sourceObj.get(prop));
                        }
                    }
                    for (String prop : targetObj.keySet()) {
                        if (!prop.equals("sounds") && !prop.equals("replace")) {
                            mergedEvent.add(prop, targetObj.get(prop));
                        }
                    }

                    merged.add(key, mergedEvent);
                }
            } else {
                merged.add(key, targetEvent);
            }
        }

        return merged;
    }
}
