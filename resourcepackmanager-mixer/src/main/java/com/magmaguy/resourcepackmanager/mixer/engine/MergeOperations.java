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
    private static final int LAST_PRE_MINOR_CLIENT_PACK_FORMAT = 64;

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

        // Legacy vanilla item model override files get a special non-recursive merge:
        // only the `overrides` array is combined, while display/texture arrays remain
        // higher-priority-wins. ItemsAdder and older packs can still emit these even
        // on newer servers.
        if (isLegacyItemModel(file)) {
            return true;
        }

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

    public void sanitizeMergedModels(File resourcePackRoot) {
        File assetsDir = new File(resourcePackRoot, "assets");
        if (!assetsDir.exists() || !assetsDir.isDirectory()) return;

        int[] stats = new int[2];
        sanitizeModelsRecursively(assetsDir, stats);
        if (stats[0] > 0 || stats[1] > 0) {
            logger.collision("Sanitized merged models: added particle textures to " + stats[0]
                    + " models, clamped invalid UVs in " + stats[1] + " models");
        }
    }

    private void sanitizeModelsRecursively(File file, int[] stats) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) return;
            for (File child : children) {
                sanitizeModelsRecursively(child, stats);
            }
            return;
        }

        String path = file.getPath().replace("\\", "/");
        if (!path.endsWith(".json") || !path.contains("/models/")) return;

        JsonObject json = readJsonFile(file);
        if (json == null) return;

        boolean addedParticle = addMissingParticleTexture(json);
        boolean clampedUvs = clampModelUvs(json);
        if (!addedParticle && !clampedUvs) return;

        try (FileWriter writer = new FileWriter(file)) {
            new Gson().toJson(json, writer);
        } catch (IOException e) {
            logger.warn("Failed to sanitize model JSON: " + file.getPath());
            return;
        }

        if (addedParticle) stats[0]++;
        if (clampedUvs) stats[1]++;
    }

    private boolean addMissingParticleTexture(JsonObject json) {
        if (!json.has("textures") || !json.get("textures").isJsonObject()) return false;
        JsonObject textures = json.getAsJsonObject("textures");
        if (textures.has("particle")) return false;

        for (String key : textures.keySet()) {
            JsonElement value = textures.get(key);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) continue;
            String texture = value.getAsString();
            if (texture == null || texture.isBlank() || texture.startsWith("#")) continue;
            textures.addProperty("particle", texture);
            return true;
        }

        return false;
    }

    private boolean clampModelUvs(JsonObject json) {
        if (!json.has("elements") || !json.get("elements").isJsonArray()) return false;

        boolean changed = false;
        for (JsonElement element : json.getAsJsonArray("elements")) {
            if (!element.isJsonObject()) continue;
            JsonObject elementObject = element.getAsJsonObject();
            if (!elementObject.has("faces") || !elementObject.get("faces").isJsonObject()) continue;

            JsonObject faces = elementObject.getAsJsonObject("faces");
            for (String faceName : faces.keySet()) {
                JsonElement faceElement = faces.get(faceName);
                if (!faceElement.isJsonObject()) continue;
                JsonObject face = faceElement.getAsJsonObject();
                if (!face.has("uv") || !face.get("uv").isJsonArray()) continue;

                JsonArray uv = face.getAsJsonArray("uv");
                for (int i = 0; i < uv.size(); i++) {
                    JsonElement coordinate = uv.get(i);
                    if (!coordinate.isJsonPrimitive() || !coordinate.getAsJsonPrimitive().isNumber()) continue;

                    double value = coordinate.getAsDouble();
                    double clamped = Math.max(0.0, Math.min(16.0, value));
                    if (Double.compare(value, clamped) != 0) {
                        uv.set(i, new JsonPrimitive(clamped));
                        changed = true;
                    }
                }
            }
        }

        return changed;
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
            mergePackFormatDeclaration(sourcePack, targetPack);
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
            if (key.equals("supported_formats")) continue;
            if (!target.has(key)) {
                target.add(key, source.get(key));
            }
        }

        try (FileWriter writer = new FileWriter(targetFile)) {
            new Gson().toJson(target, writer);
        }

        logger.collision("Merged pack.mcmeta: " + targetFile.getPath());
    }

    private void mergePackFormatDeclaration(JsonObject sourcePack, JsonObject targetPack) {
        boolean sourceHasRange = hasExplicitFormatRange(sourcePack);
        boolean targetHasRange = hasExplicitFormatRange(targetPack);

        if (!sourceHasRange && !targetHasRange) {
            if (sourcePack.has("pack_format") && targetPack.has("pack_format")) {
                int sourceFormat = sourcePack.get("pack_format").getAsInt();
                int targetFormat = targetPack.get("pack_format").getAsInt();
                targetPack.addProperty("pack_format", Math.max(sourceFormat, targetFormat));
            }
            return;
        }

        int[] sourceRange = readPackFormatRange(sourcePack);
        int[] targetRange = readPackFormatRange(targetPack);

        if (sourceRange == null && targetRange == null) return;
        if (sourceRange == null) {
            normalizePackFormatDeclaration(targetPack, targetRange[0], targetRange[1]);
            return;
        }
        if (targetRange == null) {
            normalizePackFormatDeclaration(targetPack, sourceRange[0], sourceRange[1]);
            return;
        }

        normalizePackFormatDeclaration(
                targetPack,
                Math.min(sourceRange[0], targetRange[0]),
                Math.max(sourceRange[1], targetRange[1]));
    }

    private boolean hasExplicitFormatRange(JsonObject pack) {
        return pack.has("min_format") || pack.has("max_format") || pack.has("supported_formats");
    }

    private int[] readPackFormatRange(JsonObject pack) {
        int packFormat = pack.has("pack_format") ? pack.get("pack_format").getAsInt() : -1;
        int[] supportedRange = parseSupportedFormatsRange(pack.get("supported_formats"));

        if (pack.has("min_format") || pack.has("max_format")) {
            int min = readFormatRangeMin(pack);
            int max = readFormatRangeMax(pack);

            if (min == Integer.MAX_VALUE) {
                min = supportedRange != null ? supportedRange[0] : packFormat;
            }
            if (max == Integer.MIN_VALUE) {
                max = supportedRange != null ? supportedRange[1] : packFormat;
            }
            if (min >= 0 && max >= 0) return new int[]{min, max};
        }

        if (supportedRange != null) return supportedRange;
        if (packFormat >= 0) return new int[]{packFormat, packFormat};
        return null;
    }

    private void normalizePackFormatDeclaration(JsonObject pack, int min, int max) {
        if (min > max) {
            int swap = min;
            min = max;
            max = swap;
        }

        if (max > LAST_PRE_MINOR_CLIENT_PACK_FORMAT) {
            pack.addProperty("min_format", min);
            pack.addProperty("max_format", max);

            if (min <= LAST_PRE_MINOR_CLIENT_PACK_FORMAT) {
                JsonArray supportedFormats = new JsonArray();
                supportedFormats.add(min);
                supportedFormats.add(LAST_PRE_MINOR_CLIENT_PACK_FORMAT);
                pack.add("supported_formats", supportedFormats);
                ensurePackFormatInRange(pack, min, max, min);
            } else {
                pack.remove("supported_formats");
                ensurePackFormatInRange(pack, min, max, min);
            }
        } else {
            pack.remove("min_format");
            pack.remove("max_format");
            JsonArray supportedFormats = new JsonArray();
            supportedFormats.add(min);
            supportedFormats.add(max);
            pack.add("supported_formats", supportedFormats);
            ensurePackFormatInRange(pack, min, max, max);
        }
    }

    private void ensurePackFormatInRange(JsonObject pack, int min, int max, int fallback) {
        if (!pack.has("pack_format")
                || !pack.get("pack_format").isJsonPrimitive()
                || !pack.getAsJsonPrimitive("pack_format").isNumber()) {
            pack.addProperty("pack_format", fallback);
            return;
        }

        int packFormat = pack.get("pack_format").getAsInt();
        if (packFormat < min || packFormat > max) {
            pack.addProperty("pack_format", fallback);
        }
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

            int[] range = parseOverlayFormatsRange(entry.get("formats"));
            if (range == null) continue;

            if (!entry.has("min_format")) entry.addProperty("min_format", range[0]);
            if (!entry.has("max_format")) entry.addProperty("max_format", range[1]);
        }
    }

    /**
     * Parse an overlay entry's {@code formats} field in any of its documented shapes
     * (single int, 2-int array, or {@code {min_inclusive, max_inclusive}} object) into
     * a [min, max] pair. Returns null when the field is missing, malformed, or otherwise
     * not a valid {@code formats} declaration. Shared by {@link #normalizeOverlayEntries}
     * (backfill) and {@link #warnOnInvalidOverlayMetadata} (validation) so both agree on
     * exactly what counts as a valid {@code formats} field.
     */
    private int[] parseOverlayFormatsRange(JsonElement formats) {
        if (formats == null) return null;
        if (formats.isJsonArray()) {
            JsonArray arr = formats.getAsJsonArray();
            if (arr.size() < 2) return null;
            return new int[]{arr.get(0).getAsInt(), arr.get(1).getAsInt()};
        }
        if (formats.isJsonObject()) {
            JsonObject obj = formats.getAsJsonObject();
            if (!obj.has("min_inclusive") || !obj.has("max_inclusive")) return null;
            return new int[]{obj.get("min_inclusive").getAsInt(), obj.get("max_inclusive").getAsInt()};
        }
        if (formats.isJsonPrimitive() && formats.getAsJsonPrimitive().isNumber()) {
            int v = formats.getAsInt();
            return new int[]{v, v};
        }
        return null;
    }

    /**
     * Validate the merged {@code pack.mcmeta} overlay entries and warn loudly about any that
     * MC 1.21.9+ clients will reject. WARN ONLY — this never rewrites user content.
     *
     * <p>On pack format 65+ (Minecraft 1.21.9+), an overlay entry whose declared range dips
     * below the old/new boundary ({@link #LAST_PRE_MINOR_CLIENT_PACK_FORMAT}) must carry a valid
     * {@code formats} field alongside {@code min_format}/{@code max_format}. If it is missing or
     * malformed, the client rejects the entire pack with "Overlay '...' missing required field
     * formats". RSPM merges user packs and emits the final server-hosted pack, so an invalid
     * overlay from a source pack would otherwise ship silently and be rejected client-side with
     * no hint. The maintainer's decision is to NOT rewrite user content here, but to surface a
     * clear, actionable warning naming the offending overlay so admins can fix the source pack.</p>
     */
    public void warnOnInvalidOverlayMetadata(File resourcePackRoot) {
        File packMcmeta = new File(resourcePackRoot, "pack.mcmeta");
        if (!packMcmeta.exists()) return;

        JsonObject mcmeta = readJsonFile(packMcmeta);
        if (mcmeta == null || !mcmeta.has("overlays")) return;

        JsonObject overlays = mcmeta.getAsJsonObject("overlays");
        if (!overlays.has("entries")) return;

        for (JsonElement element : overlays.getAsJsonArray("entries")) {
            if (!element.isJsonObject()) continue;
            JsonObject entry = element.getAsJsonObject();

            // Only entries whose declared range dips below the old/new boundary need a `formats`
            // field for 1.21.9+ clients. Use min_format when present (that's the lower bound the
            // client checks); fall back to formats' own lower bound so an entry with only a
            // `formats` field is still evaluated.
            int min = overlayDeclaredMinFormat(entry);
            if (min > LAST_PRE_MINOR_CLIENT_PACK_FORMAT) continue;

            if (entry.has("formats") && parseOverlayFormatsRange(entry.get("formats")) != null) continue;

            String name = entry.has("directory") ? entry.get("directory").getAsString() : "<unnamed>";
            logger.warn("Overlay '" + name + "' in the merged pack is missing a valid 'formats' field; "
                    + "MC 1.21.9+ clients will reject this pack. Fix the source pack's pack.mcmeta overlay entry.");
        }
    }

    /**
     * Determine the lower bound the client uses when deciding whether an overlay entry needs a
     * {@code formats} field. Prefers an explicit {@code min_format}, then the lower bound of a
     * valid {@code formats} declaration. Returns Integer.MAX_VALUE when no lower bound can be
     * determined so the caller treats the entry as "does not dip below the boundary" and skips it.
     */
    private int overlayDeclaredMinFormat(JsonObject entry) {
        if (entry.has("min_format") && entry.get("min_format").isJsonPrimitive()
                && entry.getAsJsonPrimitive("min_format").isNumber()) {
            return entry.get("min_format").getAsInt();
        }
        if (entry.has("formats")) {
            int[] range = parseOverlayFormatsRange(entry.get("formats"));
            if (range != null) return range[0];
        }
        return Integer.MAX_VALUE;
    }

    public boolean isLegacyItemModel(File file) {
        return file.getPath().replace("\\", "/").contains("/minecraft/models/item/");
    }

    public JsonObject mergeLegacyItemModelOverrides(JsonObject source, JsonObject target) {
        if (!source.has("overrides") || !source.get("overrides").isJsonArray()) {
            return target;
        }

        JsonObject merged = target.deepCopy();
        JsonArray mergedOverrides = new JsonArray();
        Set<String> existingKeys = new HashSet<>();

        if (target.has("overrides") && target.get("overrides").isJsonArray()) {
            for (JsonElement element : target.getAsJsonArray("overrides")) {
                mergedOverrides.add(element);
                existingKeys.add(legacyOverrideKey(element));
            }
        }

        for (JsonElement element : source.getAsJsonArray("overrides")) {
            String key = legacyOverrideKey(element);
            if (existingKeys.add(key)) {
                mergedOverrides.add(element);
            }
        }

        if (mergedOverrides.size() > 0) {
            merged.add("overrides", mergedOverrides);
        }
        return merged;
    }

    private String legacyOverrideKey(JsonElement override) {
        try {
            JsonObject object = override.getAsJsonObject();
            JsonObject predicate = object.getAsJsonObject("predicate");
            JsonElement customModelData = predicate.get("custom_model_data");
            if (customModelData != null) {
                return "custom_model_data:" + customModelData;
            }
        } catch (Exception ignored) {
            // Fall through to full JSON signature.
        }
        return override.toString();
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
