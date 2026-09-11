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
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
     * @return the merged file on success, {@code null} when there is no mapping
     * content or the complete input set cannot be merged safely
     */
    public File merge(List<File> inputs, File output) {
        if (output == null) {
            logger.warn("[BedrockMappingsMerger] output is null; aborting merge.");
            return null;
        }

        List<File> sources = inputs == null ? Collections.emptyList() : inputs;
        if (sources.isEmpty()) {
            logger.info("[BedrockMappingsMerger] No mapping inputs supplied; producing no merged mappings.");
            deleteOutputIfExists(output);
            return null;
        }

        // A partial mapping set can pair custom-item definitions with the wrong
        // Bedrock pack. Fail the complete merge on any unreadable or malformed
        // backend input so callers retain their last-good published pair.
        List<JsonObject> parsed = new ArrayList<>(sources.size());
        JsonElement formatVersionFromFirst = null;

        for (int i = 0; i < sources.size(); i++) {
            File f = sources.get(i);
            if (f == null || !f.isFile()) {
                logger.warn("[BedrockMappingsMerger] Input #" + i + " is missing or not a file; aborting: "
                        + (f == null ? "null" : f.getAbsolutePath()));
                return null;
            }
            JsonObject root = parseJsonOrNull(f);
            if (root == null) {
                logger.warn("[BedrockMappingsMerger] Could not parse input #" + i + ": "
                        + f.getAbsolutePath());
                return null;
            }
            if (!root.has("items") || !root.get("items").isJsonObject()) {
                logger.warn("[BedrockMappingsMerger] Input #" + i
                        + " has no object-valued items field; aborting.");
                return null;
            }
            JsonElement candidateFormat = root.has("format_version")
                    ? root.get("format_version") : null;
            if (formatVersionFromFirst == null && candidateFormat != null) {
                formatVersionFromFirst = candidateFormat;
            } else if (candidateFormat != null
                    && formatVersionFromFirst != null
                    && !candidateFormat.equals(formatVersionFromFirst)) {
                logger.warn("[BedrockMappingsMerger] Input #" + i
                        + " uses a different format_version; aborting.");
                return null;
            }
            parsed.add(root);
        }

        // Accumulate per-base-item entry lists. LinkedHashMap on the outer map preserves
        // first-seen order across the union of base items, which keeps diff-friendly
        // determinism when inputs are ordered consistently.
        Map<String, List<JsonObject>> byBase = new LinkedHashMap<>();
        // (baseItem, bedrockId) -> backendIndexOfLastWriter, for collision messages.
        Map<String, Integer> ownerByKey = new LinkedHashMap<>();

        for (int i = 0; i < parsed.size(); i++) {
            JsonObject root = parsed.get(i);
            JsonObject items = root.getAsJsonObject("items");

            for (String baseItem : items.keySet()) {
                JsonElement defsEl = items.get(baseItem);
                if (!defsEl.isJsonArray()) {
                    logger.warn("[BedrockMappingsMerger] Backend #" + i + " base-item '" + baseItem
                            + "' is not an array; aborting.");
                    return null;
                }
                JsonArray defs = defsEl.getAsJsonArray();
                List<JsonObject> bucket = byBase.computeIfAbsent(baseItem, k -> new ArrayList<>());

                for (JsonElement el : defs) {
                    if (!el.isJsonObject()) {
                        logger.warn("[BedrockMappingsMerger] Backend #" + i
                                + " has a non-object definition under '" + baseItem
                                + "'; aborting.");
                        return null;
                    }
                    JsonObject def = el.getAsJsonObject();
                    String bedrockId = def.has("bedrock_identifier")
                            && def.get("bedrock_identifier").isJsonPrimitive()
                            ? def.get("bedrock_identifier").getAsString() : null;

                    if (bedrockId == null || bedrockId.isEmpty()) {
                        logger.warn("[BedrockMappingsMerger] Backend #" + i + " has a definition under '"
                                + baseItem + "' with missing/empty bedrock_identifier; aborting.");
                        return null;
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

        deduplicateGeyserConflicts(byBase);

        return writeMerged(output, formatVersionFromFirst, byBase);
    }

    /**
     * Collapses definitions that share one {@link GeyserMappingIdentity Geyser conflict
     * identity} (same item + same {@code model} + same parsed predicates) but carry
     * different bedrock identifiers. The identifier dedup above cannot catch these:
     * RSPM's identifier hash includes the resolved source model, so two backends whose
     * packs resolve the same custom_model_data (or other matcher) to different models
     * produce distinct identifiers for what is one matcher to Geyser — which then rejects
     * all but the first at boot with "both entries have the same predicates".
     *
     * <p>Winner per identity: a modern {@code type=definition} entry beats a
     * {@code type=legacy} one; between entries of the same type the later one wins,
     * consistent with the merger's last-writer-wins identifier policy. Emits a single
     * summary line naming every collapsed pair.
     */
    private void deduplicateGeyserConflicts(Map<String, List<JsonObject>> byBase) {
        List<String> dedupNotes = new ArrayList<>();
        for (Map.Entry<String, List<JsonObject>> e : byBase.entrySet()) {
            String baseItem = e.getKey();
            List<JsonObject> bucket = e.getValue();
            if (bucket.size() < 2) continue;

            // identity -> index of the entry currently winning that identity.
            Map<String, Integer> winnerByIdentity = new LinkedHashMap<>();
            Set<Integer> dropped = new LinkedHashSet<>();
            for (int i = 0; i < bucket.size(); i++) {
                JsonObject def = bucket.get(i);
                String identity = GeyserMappingIdentity.of(baseItem, def);
                // Entries whose identity cannot be computed are never deduplicated —
                // guessing here could silently drop a valid mapping.
                if (identity == null) continue;
                Integer previous = winnerByIdentity.get(identity);
                if (previous == null) {
                    winnerByIdentity.put(identity, i);
                    continue;
                }
                boolean keepPrevious = isDefinitionType(bucket.get(previous)) && !isDefinitionType(def);
                int winner = keepPrevious ? previous : i;
                int loser = keepPrevious ? i : previous;
                winnerByIdentity.put(identity, winner);
                dropped.add(loser);
                dedupNotes.add("'" + baseItem + "' ("
                        + GeyserMappingIdentity.describeMatcher(baseItem, bucket.get(winner))
                        + "): kept " + bedrockIdentifierOf(bucket.get(winner))
                        + ", dropped " + bedrockIdentifierOf(bucket.get(loser)));
            }
            if (dropped.isEmpty()) continue;

            List<JsonObject> survivors = new ArrayList<>(bucket.size() - dropped.size());
            for (int i = 0; i < bucket.size(); i++) {
                if (!dropped.contains(i)) survivors.add(bucket.get(i));
            }
            bucket.clear();
            bucket.addAll(survivors);
        }

        if (!dedupNotes.isEmpty()) {
            logger.info("[BedrockMappingsMerger] Deduplicated " + dedupNotes.size()
                    + " definition(s) whose (item, predicate) matcher was already claimed"
                    + " (Geyser registers only one definition per matcher): "
                    + GeyserMappingIdentity.summarizeNotes(dedupNotes, 8));
        }
    }

    private static boolean isDefinitionType(JsonObject def) {
        return def.has("type") && def.get("type").isJsonPrimitive()
                && "definition".equals(def.get("type").getAsString());
    }

    private static String bedrockIdentifierOf(JsonObject def) {
        return def.has("bedrock_identifier") && def.get("bedrock_identifier").isJsonPrimitive()
                ? def.get("bedrock_identifier").getAsString() : "<no identifier>";
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
        File tmpFile = null;
        try {
            Path outputDirectory = parent == null
                    ? Path.of(".").toAbsolutePath().normalize()
                    : parent.toPath();
            tmpFile = Files.createTempFile(
                    outputDirectory,
                    output.getName() + ".",
                    ".tmp").toFile();
            try (Writer w = new BufferedWriter(new FileWriter(tmpFile, StandardCharsets.UTF_8), 1 << 16)) {
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
            return null;
        } finally {
            // On success the move consumed the temporary path. On failure, make
            // sure no partial staging file survives.
            //noinspection ConstantValue
            if (tmpFile != null) {
                try {
                    Files.deleteIfExists(tmpFile.toPath());
                } catch (IOException ignored) {
                }
            }
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
