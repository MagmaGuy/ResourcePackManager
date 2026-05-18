package com.magmaguy.resourcepackmanager.bedrock.generic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.magmaguy.magmacore.util.Logger;

import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads and caches Java resource-pack assets — items definitions, models (with parent chain),
 * and equipment files — from the merged Java pack. One instance per conversion run.
 *
 * <p>Parent-chain merge rules: child overrides parent's keys; {@code textures} and {@code display}
 * are merged per-slot (shallow); {@code elements} replaces the parent's array entirely; chain
 * capped at {@link #MAX_PARENT_DEPTH} (per Option J plan, Phase 9 hardening).
 *
 * <p>Flat-builtin and handheld detection mirrors Rainbow's
 * {@code BedrockGeometryContext.java:22-24, 40}.
 */
public final class AssetResolver {

    private static final int MAX_PARENT_DEPTH = 10;
    private static final Set<String> FLAT_BUILTIN_ROOTS = Set.of(
            "minecraft:item/generated",
            "minecraft:builtin/generated"
    );
    private static final Set<String> HANDHELD_PARENTS = Set.of(
            "minecraft:item/handheld",
            "minecraft:item/handheld_rod",
            "minecraft:item/handheld_mace"
    );

    private final File mergedJavaPack;
    private final Map<String, Optional<JsonObject>> rawModelCache = new HashMap<>();
    private final Map<String, Optional<ResolvedModel>> resolvedModelCache = new HashMap<>();
    private final Map<String, Optional<JsonObject>> equipmentCache = new HashMap<>();

    public AssetResolver(File mergedJavaPack) {
        this.mergedJavaPack = mergedJavaPack;
    }

    public Optional<JsonObject> getRawModel(String modelRef) {
        return rawModelCache.computeIfAbsent(modelRef, this::loadRawModel);
    }

    public Optional<ResolvedModel> resolveModel(String modelRef) {
        return resolvedModelCache.computeIfAbsent(modelRef, this::resolveModelImpl);
    }

    public Optional<JsonObject> getEquipment(String equipmentRef) {
        return equipmentCache.computeIfAbsent(equipmentRef, this::loadEquipment);
    }

    private Optional<JsonObject> loadRawModel(String modelRef) {
        return loadJsonAt(referenceToPath(modelRef, "models"));
    }

    private Optional<JsonObject> loadEquipment(String equipmentRef) {
        return loadJsonAt(referenceToPath(equipmentRef, "equipment"));
    }

    /** Converts {@code "ns:path"} (or just {@code "path"}) into {@code assets/ns/<kind>/path.json}. */
    private File referenceToPath(String reference, String kind) {
        String namespace;
        String path;
        int colon = reference.indexOf(':');
        if (colon >= 0) {
            namespace = reference.substring(0, colon);
            path = reference.substring(colon + 1);
        } else {
            namespace = "minecraft";
            path = reference;
        }
        return new File(mergedJavaPack, "assets/" + namespace + "/" + kind + "/" + path + ".json");
    }

    private Optional<JsonObject> loadJsonAt(File file) {
        if (!file.isFile()) return Optional.empty();
        try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) return Optional.empty();
            return Optional.of(parsed.getAsJsonObject());
        } catch (Exception e) {
            Logger.warn("[BedrockConverter] Failed to parse JSON " + file.getPath() + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<ResolvedModel> resolveModelImpl(String modelRef) {
        // Walk the parent chain, collecting every visited ref (for cycle detection + handheld scan).
        Set<String> chain = new LinkedHashSet<>();
        JsonObject merged = new JsonObject();
        String currentRef = modelRef;
        String rootParent = null;
        boolean handheld = false;
        int depth = 0;

        while (currentRef != null) {
            if (depth++ >= MAX_PARENT_DEPTH) {
                Logger.warn("[BedrockConverter] Parent chain exceeded MAX_PARENT_DEPTH (" + MAX_PARENT_DEPTH
                        + ") starting from " + modelRef + "; stopping");
                break;
            }
            if (!chain.add(currentRef)) {
                Logger.warn("[BedrockConverter] Parent chain cycle detected starting from "
                        + modelRef + " (loop on " + currentRef + "); stopping");
                break;
            }
            if (HANDHELD_PARENTS.contains(currentRef)) handheld = true;

            Optional<JsonObject> nodeOpt = getRawModel(currentRef);
            if (nodeOpt.isEmpty()) {
                // Treat the missing reference as the root parent — typical for vanilla parents
                // we don't ship (item/generated, item/handheld, etc.).
                rootParent = currentRef;
                break;
            }
            JsonObject node = nodeOpt.get();
            mergeParentIntoChild(node, merged);

            String parent = node.has("parent") && node.get("parent").isJsonPrimitive()
                    ? node.get("parent").getAsString() : null;
            if (parent == null) {
                // No further parent — current ref is itself the root.
                rootParent = currentRef;
                break;
            }
            // Normalise (default namespace = minecraft)
            if (!parent.contains(":")) parent = "minecraft:" + parent;
            currentRef = parent;
        }

        boolean flatBuiltin = rootParent != null && FLAT_BUILTIN_ROOTS.contains(rootParent);
        return Optional.of(new ResolvedModel(modelRef, rootParent, merged, flatBuiltin, handheld));
    }

    /**
     * Merges {@code parent}'s keys INTO {@code child}: child takes precedence. {@code textures}
     * and {@code display} merge per-slot key (shallow); {@code elements} and other keys only fill
     * if the child doesn't already have them (i.e. child wins entirely).
     */
    private void mergeParentIntoChild(JsonObject parent, JsonObject child) {
        for (Map.Entry<String, JsonElement> entry : parent.entrySet()) {
            String key = entry.getKey();
            if (key.equals("parent")) continue;
            if (!child.has(key)) {
                child.add(key, deepCopy(entry.getValue()));
                continue;
            }
            // Child already has the key — for textures/display we shallow-merge slot keys
            // the child hasn't defined; for everything else (elements, gui_light, ...) child wins.
            if ((key.equals("textures") || key.equals("display"))
                    && child.get(key).isJsonObject()
                    && entry.getValue().isJsonObject()) {
                JsonObject childObj = child.getAsJsonObject(key);
                JsonObject parentObj = entry.getValue().getAsJsonObject();
                for (Map.Entry<String, JsonElement> p : parentObj.entrySet()) {
                    if (!childObj.has(p.getKey())) {
                        childObj.add(p.getKey(), deepCopy(p.getValue()));
                    }
                }
            }
        }
    }

    private static JsonElement deepCopy(JsonElement e) {
        if (e.isJsonObject()) {
            JsonObject copy = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : e.getAsJsonObject().entrySet()) {
                copy.add(entry.getKey(), deepCopy(entry.getValue()));
            }
            return copy;
        } else if (e.isJsonArray()) {
            JsonArray copy = new JsonArray();
            for (JsonElement el : e.getAsJsonArray()) copy.add(deepCopy(el));
            return copy;
        }
        return e; // primitives & nulls are immutable
    }
}
