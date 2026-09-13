package com.magmaguy.resourcepackmanager.mixer.engine;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;

import java.io.File;
import java.io.FileReader;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class MergeOperations {
    private static final int LAST_PRE_MINOR_CLIENT_PACK_FORMAT = 64;
    private static final Pattern OVERLAY_DIRECTORY = Pattern.compile("[a-z0-9_-]+");

    private final MixerLogger logger;

    public MergeOperations(MixerLogger logger) {
        this.logger = logger;
    }

    /**
     * How {@link #mergeColliding(File, File)} resolved a single file collision. Callers that log
     * collisions map each outcome to their own message; callers that do not can ignore the result.
     */
    public enum CollisionOutcome {
        MERGED_PACK_MCMETA,
        KEPT_NON_JSON,
        KEPT_NON_MERGEABLE_JSON,
        BOTH_UNREADABLE,
        KEPT_SOURCE_UNREADABLE,
        REPLACED_UNREADABLE_TARGET,
        MERGED
    }

    /**
     * Resolves a collision between {@code sourceFile} (lower priority) and {@code targetFile}
     * (higher priority) in place, mutating {@code targetFile} when a merge or replacement applies.
     *
     * @return the outcome describing how the collision was resolved
     */
    public CollisionOutcome mergeColliding(File sourceFile, File targetFile) throws IOException {
        if (targetFile.getName().equals("pack.mcmeta")) {
            this.mergePackMcmeta(sourceFile, targetFile);
            return CollisionOutcome.MERGED_PACK_MCMETA;
        }

        if (!targetFile.getName().endsWith(".json")) {
            return CollisionOutcome.KEPT_NON_JSON;
        }

        if (!this.isMergeableJsonFile(targetFile)) {
            return CollisionOutcome.KEPT_NON_MERGEABLE_JSON;
        }

        JsonObject json1 = this.readJsonFile(sourceFile);
        JsonObject json2 = this.readJsonFile(targetFile);

        if (json1 == null && json2 == null) {
            return CollisionOutcome.BOTH_UNREADABLE;
        }
        if (json1 == null) return CollisionOutcome.KEPT_SOURCE_UNREADABLE;
        if (json2 == null) {
            Files.copy(sourceFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return CollisionOutcome.REPLACED_UNREADABLE_TARGET;
        }

        boolean legacyItemModel = this.isLegacyItemModel(targetFile);
        JsonObject mergedJson;
        if (legacyItemModel) {
            mergedJson = this.mergeLegacyItemModelOverrides(json1, json2);
        } else if (this.isItemsFile(targetFile)) {
            mergedJson = this.mergeItemsModels(json1, json2);
        } else if (targetFile.getName().equals("sounds.json")) {
            mergedJson = this.mergeSoundsJson(json1, json2);
        } else {
            mergedJson = this.mergeJsonObjects(json1, json2);
        }

        // Legacy item models (pre-1.21.4) sort overrides by custom_model_data so
        // numeric ordering survives a deep merge — without this the higher CMD
        // entries can leapfrog lower ones and the wrong model resolves at runtime.
        if (legacyItemModel && mergedJson.has("overrides")) {
            this.sortModelOverrides(mergedJson);
        }

        try (Writer writer = new BufferedWriter(new FileWriter(targetFile), 1 << 16)) {
            new Gson().toJson(mergedJson, writer);
        }

        return CollisionOutcome.MERGED;
    }

    private JsonObject readJsonFile(File file) {
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
    private boolean isMergeableJsonFile(File file) {
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

    private JsonObject mergeJsonObjects(JsonObject json1, JsonObject json2) {
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

        try (Writer writer = new BufferedWriter(new FileWriter(overlayAtlas), 1 << 16)) {
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

        try (Writer writer = new BufferedWriter(new FileWriter(file), 1 << 16)) {
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

    private void mergePackMcmeta(File sourceFile, File targetFile) throws IOException {
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
            // Keep both overlay range representations while resolving this collision. A final pass
            // after assembly repeats the normalization and validates every entry, including the
            // single-input case that never reaches this method.
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

        try (Writer writer = new BufferedWriter(new FileWriter(targetFile), 1 << 16)) {
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
     * Migrates overlay ranges across Minecraft's 1.21.9 metadata boundary. Numeric
     * {@code min_format}/{@code max_format} fields are always required. The removed legacy
     * {@code formats} field is required on every entry only when at least one range still includes
     * a pre-65 resource-pack format; otherwise the new schema forbids it.
     */
    private boolean normalizeOverlayEntries(JsonArray entries) throws IOException {
        boolean changed = false;
        boolean requiresLegacyFormats = false;

        for (int index = 0; index < entries.size(); index++) {
            JsonElement element = entries.get(index);
            if (!element.isJsonObject()) {
                throw new IOException("Overlay entry #" + index + " is not a JSON object");
            }
            JsonObject entry = element.getAsJsonObject();
            String directory = readOverlayDirectory(entry);
            if (directory == null) {
                throw new IOException("Overlay entry #" + index
                        + " has an invalid directory; use only lowercase letters, digits, _, and -");
            }

            int[] formatsRange = parseOverlayFormatsRange(entry.get("formats"));
            OverlayVersionRange numericRange = parseNumericOverlayRange(entry);
            boolean hasMin = entry.has("min_format");
            boolean hasMax = entry.has("max_format");

            if (!hasMin && !hasMax && isValidOverlayRange(formatsRange)) {
                entry.addProperty("min_format", formatsRange[0]);
                entry.addProperty("max_format", formatsRange[1]);
                numericRange = parseNumericOverlayRange(entry);
                changed = true;
            }

            if (numericRange == null || !numericRange.isValid()) {
                throw new IOException("Overlay '" + directory
                        + "' must declare valid min_format and max_format bounds");
            }
            if (numericRange.min().major() <= LAST_PRE_MINOR_CLIENT_PACK_FORMAT) {
                requiresLegacyFormats = true;
            }
        }

        for (JsonElement element : entries) {
            JsonObject entry = element.getAsJsonObject();
            OverlayVersionRange numericRange = parseNumericOverlayRange(entry);

            if (!requiresLegacyFormats) {
                if (entry.has("formats")) {
                    entry.remove("formats");
                    changed = true;
                }
                continue;
            }

            if (entry.has("formats")) continue;

            JsonObject formats = new JsonObject();
            formats.addProperty("min_inclusive", numericRange.min().major());
            int legacyMax = numericRange.min().major() <= LAST_PRE_MINOR_CLIENT_PACK_FORMAT
                    ? Math.min(numericRange.max().major(), LAST_PRE_MINOR_CLIENT_PACK_FORMAT)
                    : numericRange.max().major();
            formats.addProperty("max_inclusive", legacyMax);
            entry.add("formats", formats);
            changed = true;
        }
        return changed;
    }

    private OverlayVersionRange parseNumericOverlayRange(JsonObject entry) {
        if (!entry.has("min_format") || !entry.has("max_format")) return null;
        OverlayVersion min = parseOverlayVersion(entry.get("min_format"), false);
        OverlayVersion max = parseOverlayVersion(entry.get("max_format"), true);
        return min == null || max == null ? null : new OverlayVersionRange(min, max);
    }

    private OverlayVersion parseOverlayVersion(JsonElement element, boolean maximum) {
        if (element == null) return null;

        if (element.isJsonArray()) {
            JsonArray values = element.getAsJsonArray();
            if (values.size() < 1 || values.size() > 2) return null;
            Integer major = parseInteger(values.get(0));
            Integer minor = values.size() == 2
                    ? parseInteger(values.get(1))
                    : (maximum ? Integer.MAX_VALUE : 0);
            if (major == null || minor == null || major < 0 || minor < 0) return null;
            return new OverlayVersion(major, minor);
        }

        Integer major = parseInteger(element);
        if (major == null || major < 0) return null;
        return new OverlayVersion(major, maximum ? Integer.MAX_VALUE : 0);
    }

    /**
     * Parse an overlay entry's {@code formats} field in any of its documented shapes
     * (single int, 2-int array, or {@code {min_inclusive, max_inclusive}} object) into
     * a [min, max] pair. Returns null when the field is missing, malformed, or otherwise
     * not a valid {@code formats} declaration. Normalization and final validation share this
     * parser so they agree on exactly what counts as a valid {@code formats} field.
     */
    private int[] parseOverlayFormatsRange(JsonElement formats) {
        if (formats == null) return null;
        if (formats.isJsonArray()) {
            JsonArray arr = formats.getAsJsonArray();
            if (arr.size() != 2) return null;
            Integer min = parseInteger(arr.get(0));
            Integer max = parseInteger(arr.get(1));
            return min == null || max == null ? null : new int[]{min, max};
        }
        if (formats.isJsonObject()) {
            JsonObject obj = formats.getAsJsonObject();
            if (!obj.has("min_inclusive") || !obj.has("max_inclusive")) return null;
            Integer min = parseInteger(obj.get("min_inclusive"));
            Integer max = parseInteger(obj.get("max_inclusive"));
            return min == null || max == null ? null : new int[]{min, max};
        }
        Integer value = parseInteger(formats);
        if (value != null) return new int[]{value, value};
        return null;
    }

    private Integer parseInteger(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            return null;
        }
    }

    private boolean isValidOverlayRange(int[] range) {
        return range != null && range[0] >= 0 && range[0] <= range[1];
    }

    private record OverlayVersion(int major, int minor) implements Comparable<OverlayVersion> {
        @Override
        public int compareTo(OverlayVersion other) {
            int majorComparison = Integer.compare(major, other.major);
            return majorComparison != 0 ? majorComparison : Integer.compare(minor, other.minor);
        }
    }

    private record OverlayVersionRange(OverlayVersion min, OverlayVersion max) {
        private boolean isValid() {
            return min.compareTo(max) <= 0;
        }
    }

    /**
     * Normalizes the merged {@code pack.mcmeta} at the final publication boundary. Minecraft's
     * overlay schema has used both {@code formats} and numeric {@code min_format}/{@code max_format}
     * declarations. The published pack carries both representations so mixed-version clients can
     * interpret every entry consistently.
     */
    public void normalizeAndValidateOverlayMetadata(File resourcePackRoot) throws IOException {
        File packMcmeta = new File(resourcePackRoot, "pack.mcmeta");
        if (!packMcmeta.exists()) return;

        JsonObject mcmeta = readJsonFile(packMcmeta);
        if (mcmeta == null) {
            throw new IOException("Unable to validate merged pack.mcmeta before publication");
        }
        if (!mcmeta.has("overlays")) return;

        JsonElement overlaysElement = mcmeta.get("overlays");
        if (!overlaysElement.isJsonObject()) {
            throw new IOException("Merged pack.mcmeta overlays must be a JSON object");
        }
        JsonObject overlays = overlaysElement.getAsJsonObject();
        if (!overlays.has("entries")) {
            throw new IOException("Merged pack.mcmeta overlays section is missing entries");
        }

        JsonElement entriesElement = overlays.get("entries");
        if (!entriesElement.isJsonArray()) {
            throw new IOException("Merged pack.mcmeta overlay entries must be a JSON array");
        }
        JsonArray entries = entriesElement.getAsJsonArray();
        boolean changed = normalizeOverlayEntries(entries);
        validateOverlayEntries(entries);
        if (changed) {
            try (Writer writer = new BufferedWriter(new FileWriter(packMcmeta), 1 << 16)) {
                new Gson().toJson(mcmeta, writer);
            }
        }
    }

    private void validateOverlayEntries(JsonArray entries) throws IOException {
        boolean requiresLegacyFormats = false;
        for (JsonElement element : entries) {
            if (!element.isJsonObject()) continue;
            OverlayVersionRange numericRange = parseNumericOverlayRange(element.getAsJsonObject());
            if (numericRange != null && numericRange.isValid()
                    && numericRange.min().major() <= LAST_PRE_MINOR_CLIENT_PACK_FORMAT) {
                requiresLegacyFormats = true;
            }
        }

        for (int index = 0; index < entries.size(); index++) {
            JsonElement element = entries.get(index);
            if (!element.isJsonObject()) {
                throw new IOException("Overlay entry #" + index + " is not a JSON object");
            }

            JsonObject entry = element.getAsJsonObject();
            String directory = readOverlayDirectory(entry);
            if (directory == null) {
                throw new IOException("Overlay entry #" + index + " is missing a non-empty string directory");
            }

            OverlayVersionRange numericRange = parseNumericOverlayRange(entry);
            if (numericRange == null || !numericRange.isValid()) {
                throw new IOException("Overlay '" + directory
                        + "' must declare valid min_format and max_format bounds");
            }

            if (!requiresLegacyFormats) {
                if (entry.has("formats")) {
                    throw new IOException("Overlay '" + directory
                            + "' retains formats even though every range starts at format 65 or newer");
                }
                continue;
            }

            int[] formatsRange = parseOverlayFormatsRange(entry.get("formats"));
            if (!isValidOverlayRange(formatsRange)) {
                throw new IOException("Overlay '" + directory
                        + "' needs a valid formats range because this pack supports a pre-65 format");
            }
            int expectedLegacyMin = numericRange.min().major();
            int expectedLegacyMax = numericRange.min().major() <= LAST_PRE_MINOR_CLIENT_PACK_FORMAT
                    ? Math.min(numericRange.max().major(), LAST_PRE_MINOR_CLIENT_PACK_FORMAT)
                    : numericRange.max().major();
            if (formatsRange[0] != expectedLegacyMin
                    || formatsRange[1] != expectedLegacyMax) {
                throw new IOException("Overlay '" + directory + "' declares conflicting ranges: formats="
                        + formatsRange[0] + ".." + formatsRange[1] + ", min_format/max_format="
                        + numericRange.min().major() + ".." + numericRange.max().major());
            }
        }
    }

    private String readOverlayDirectory(JsonObject entry) {
        if (!entry.has("directory")) return null;
        JsonElement directory = entry.get("directory");
        if (!directory.isJsonPrimitive() || !directory.getAsJsonPrimitive().isString()) return null;
        String value = directory.getAsString();
        return OVERLAY_DIRECTORY.matcher(value).matches() ? value : null;
    }

    private boolean isLegacyItemModel(File file) {
        return file.getPath().replace("\\", "/").contains("/minecraft/models/item/");
    }

    private JsonObject mergeLegacyItemModelOverrides(JsonObject source, JsonObject target) {
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

    private boolean isItemsFile(File file) {
        String path = file.getPath().replace("\\", "/");
        return path.contains("/items/") && !path.contains("/models/item/");
    }

    private void sortModelOverrides(JsonObject modelJson) {
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

    private JsonObject mergeItemsModels(JsonObject source, JsonObject target) {
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
            copyNonModelKeys(source, target);
            return target;
        }

        if (sourceType.equals("select") && targetType.equals("select")) {
            String sourceProp = sourceModel.has("property") ? sourceModel.get("property").getAsString() : "";
            String targetProp = targetModel.has("property") ? targetModel.get("property").getAsString() : "";
            if (sourceProp.equals(targetProp)) {
                mergeSelectCases(sourceModel, targetModel);
                target.add("model", targetModel);
                copyNonModelKeys(source, target);
                return target;
            }
        }

        // Incompatible types or non-dispatch model: higher priority (target) wins atomically.
        return target;
    }

    /**
     * Copies every top-level key except {@code model} from {@code source} into
     * {@code target} when the target doesn't already define it (higher priority
     * wins on conflicts).
     */
    private void copyNonModelKeys(JsonObject source, JsonObject target) {
        for (String key : source.keySet()) {
            if (!key.equals("model") && !target.has(key)) {
                target.add(key, source.get(key));
            }
        }
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

    private JsonObject mergeSoundsJson(JsonObject source, JsonObject target) {
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
