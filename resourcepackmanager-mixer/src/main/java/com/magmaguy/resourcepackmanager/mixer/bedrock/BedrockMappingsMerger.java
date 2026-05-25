package com.magmaguy.resourcepackmanager.mixer.bedrock;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.magmaguy.resourcepackmanager.mixer.engine.MixerLogger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges N {@code rspm_geyser_mappings.json} files (one per backend) into a single
 * Geyser custom-mappings JSON for the proxy.
 *
 * <p>Input shape (produced by
 * {@code com.magmaguy.resourcepackmanager.bedrock.generic.GenericGeyserMappingBuilder.merge}):
 * <pre>
 * {
 *   "format_version": 2,
 *   "items": {
 *     "minecraft:base_item": [definition, ...],
 *     ...
 *   }
 * }
 * </pre>
 *
 * <p>Merge rules:
 * <ul>
 *   <li>Union of all base-item keys.</li>
 *   <li>For shared keys, concatenate the per-backend definition arrays.</li>
 *   <li>Dedup definitions by {@code bedrock_identifier} &mdash; on duplicate,
 *       last writer wins with a warn.</li>
 *   <li>Preserve {@code format_version} from the first input (they should all
 *       match).</li>
 * </ul>
 *
 * <p>Pure JDK + Gson. No Bukkit / Velocity / Geyser API.
 */
public final class BedrockMappingsMerger {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int DEFAULT_FORMAT_VERSION = 2;

    private final MixerLogger logger;

    public BedrockMappingsMerger(MixerLogger logger) {
        this.logger = logger;
    }

    /**
     * Merge N {@code rspm_geyser_mappings.json} files into one.
     *
     * @param inputs the per-backend mappings files, ordered by priority
     *               (later entries win on {@code bedrock_identifier} collisions)
     * @param output destination file
     * @return the merged file on success, {@code null} on failure
     */
    public File merge(List<File> inputs, File output) {
        if (output == null) {
            logger.warn("[BedrockMappingsMerger] output is null; aborting merge.");
            return null;
        }

        List<File> sources = inputs == null ? Collections.emptyList() : inputs;

        // Parse each input. Non-readable inputs are warned and skipped.
        List<JsonObject> parsed = new ArrayList<>(sources.size());
        JsonElement formatVersionFromFirst = null;

        for (int i = 0; i < sources.size(); i++) {
            File f = sources.get(i);
            if (f == null || !f.isFile()) {
                logger.warn("[BedrockMappingsMerger] Input #" + i + " is missing or not a file; skipping: "
                        + (f == null ? "null" : f.getAbsolutePath()));
                continue;
            }
            JsonObject root = parseJsonOrNull(f);
            if (root == null) {
                logger.warn("[BedrockMappingsMerger] Could not parse input #" + i + ": "
                        + f.getAbsolutePath());
                continue;
            }
            if (formatVersionFromFirst == null && root.has("format_version")) {
                formatVersionFromFirst = root.get("format_version");
            }
            parsed.add(root);
        }

        // Zero-readable-inputs path: per user policy, emit nothing rather than a
        // normalized empty mappings file. Delete any stale previous-cycle output so
        // the boot-time pre-deploy on the next proxy restart skips this network
        // entirely instead of registering an empty mappings file.
        if (parsed.isEmpty()) {
            logger.info("[BedrockMappingsMerger] No readable input mappings files; producing no merged mappings.");
            deleteOutputIfExists(output);
            return null;
        }

        // Accumulate per-base-item entry lists. LinkedHashMap on the outer map preserves
        // first-seen order across the union of base items, which keeps diff-friendly
        // determinism when inputs are ordered consistently.
        Map<String, List<JsonObject>> byBase = new LinkedHashMap<>();
        // (baseItem, bedrockId) -> backendIndexOfLastWriter, for collision messages.
        Map<String, Integer> ownerByKey = new LinkedHashMap<>();

        for (int i = 0; i < parsed.size(); i++) {
            JsonObject root = parsed.get(i);
            if (!root.has("items") || !root.get("items").isJsonObject()) continue;
            JsonObject items = root.getAsJsonObject("items");

            for (String baseItem : items.keySet()) {
                JsonElement defsEl = items.get(baseItem);
                if (!defsEl.isJsonArray()) {
                    logger.warn("[BedrockMappingsMerger] Backend #" + i + " base-item '" + baseItem
                            + "' is not an array; skipping.");
                    continue;
                }
                JsonArray defs = defsEl.getAsJsonArray();
                List<JsonObject> bucket = byBase.computeIfAbsent(baseItem, k -> new ArrayList<>());

                for (JsonElement el : defs) {
                    if (!el.isJsonObject()) continue;
                    JsonObject def = el.getAsJsonObject();
                    String bedrockId = def.has("bedrock_identifier")
                            && def.get("bedrock_identifier").isJsonPrimitive()
                            ? def.get("bedrock_identifier").getAsString() : null;

                    if (bedrockId == null || bedrockId.isEmpty()) {
                        logger.warn("[BedrockMappingsMerger] Backend #" + i + " has a definition under '"
                                + baseItem + "' with missing/empty bedrock_identifier; keeping as-is.");
                        bucket.add(def);
                        continue;
                    }

                    String key = baseItem + "|" + bedrockId;
                    Integer prevOwner = ownerByKey.get(key);
                    if (prevOwner != null) {
                        // Last writer wins: remove the previous entry from the bucket then append the new one.
                        logger.warn("[BedrockMappingsMerger] Duplicate bedrock_identifier '" + bedrockId
                                + "' under base item '" + baseItem
                                + "' between backend #" + prevOwner + " and backend #" + i
                                + "; last writer wins (backend #" + i + ").");
                        removeFirstWithBedrockId(bucket, bedrockId);
                    }
                    bucket.add(def);
                    ownerByKey.put(key, i);
                }
            }
        }

        // Post-walk empty check: every input was `{"items": {}}` or had no items keys.
        // Per user policy, emit nothing rather than write `{"items": {}}` to disk.
        if (byBase.isEmpty()) {
            logger.info("[BedrockMappingsMerger] All input mappings files are empty (no items keys); producing no merged mappings.");
            deleteOutputIfExists(output);
            return null;
        }

        return writeMerged(output, formatVersionFromFirst, byBase);
    }

    /**
     * Delete the previous-cycle merged mappings file (if any) so a downstream
     * boot-time pre-deploy on the next proxy restart skips this network instead
     * of registering an empty mappings file.
     */
    private void deleteOutputIfExists(File output) {
        if (output == null) return;
        try {
            if (Files.deleteIfExists(output.toPath())) {
                logger.info("[BedrockMappingsMerger] Deleted previous merged mappings: " + output.getAbsolutePath());
            }
        } catch (IOException e) {
            logger.warn("[BedrockMappingsMerger] Failed to delete previous merged mappings "
                    + output.getAbsolutePath() + ": " + e.getMessage());
        }
    }

    private void removeFirstWithBedrockId(List<JsonObject> bucket, String bedrockId) {
        for (int idx = 0; idx < bucket.size(); idx++) {
            JsonObject def = bucket.get(idx);
            if (def.has("bedrock_identifier")
                    && def.get("bedrock_identifier").isJsonPrimitive()
                    && bedrockId.equals(def.get("bedrock_identifier").getAsString())) {
                bucket.remove(idx);
                return;
            }
        }
    }

    private File writeMerged(File output, JsonElement formatVersion, Map<String, List<JsonObject>> byBase) {
        JsonObject items = new JsonObject();
        int total = 0;
        for (Map.Entry<String, List<JsonObject>> e : byBase.entrySet()) {
            JsonArray arr = new JsonArray();
            for (JsonObject def : e.getValue()) arr.add(def);
            items.add(e.getKey(), arr);
            total += e.getValue().size();
        }

        JsonObject root = new JsonObject();
        if (formatVersion != null) {
            root.add("format_version", formatVersion);
        } else {
            root.addProperty("format_version", DEFAULT_FORMAT_VERSION);
        }
        root.add("items", items);

        File parent = output.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            logger.warn("[BedrockMappingsMerger] Failed to create output directory: " + parent.getAbsolutePath());
            return null;
        }

        // Atomic temp-then-rename so a reader (Geyser at boot, or the proxy's deploy
        // hook) never sees a half-written JSON. Matches the convention used by
        // GenericGeyserMappingBuilder.merge.
        File tmpFile = new File(parent == null ? new File(".") : parent, output.getName() + ".tmp");
        try {
            try (FileWriter w = new FileWriter(tmpFile, StandardCharsets.UTF_8)) {
                GSON.toJson(root, w);
            }
            try {
                Files.move(tmpFile.toPath(), output.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicMoveFailed) {
                Files.move(tmpFile.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            logger.info("[BedrockMappingsMerger] Wrote merged Geyser mappings: "
                    + total + " entries across " + byBase.size() + " base items -> "
                    + output.getAbsolutePath());
            return output;
        } catch (IOException e) {
            logger.warn("[BedrockMappingsMerger] Failed to write merged mappings: " + e.getMessage());
            try {
                Files.deleteIfExists(tmpFile.toPath());
            } catch (IOException ignored) {
            }
            return null;
        }
    }

    private JsonObject parseJsonOrNull(File f) {
        try (FileReader r = new FileReader(f, StandardCharsets.UTF_8)) {
            JsonElement el = JsonParser.parseReader(r);
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
