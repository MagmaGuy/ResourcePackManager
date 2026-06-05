package com.magmaguy.resourcepackmanager.bedrock.generic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.magmaguy.resourcepackmanager.bedrock.BedrockLog;

import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Index of Minecraft atlas <em>sprite remaps</em> found in a merged Java pack.
 *
 * <p>Vanilla resource packs may rename a texture's sprite via an atlas
 * {@code single} source: a model can reference {@code "layer0": "ns:short"} while
 * the actual PNG lives somewhere else entirely, with the link declared in
 * {@code assets/<ns>/atlases/<atlas>.json}:
 * <pre>{@code { "type": "single", "resource": "inkless:vanillasets/cake_sword", "sprite": "ia:627" } }</pre>
 * The Java client stitches the {@code resource} texture into the atlas under the
 * {@code sprite} name, so {@code ia:627} resolves at render time — but only through
 * the atlas. ItemsAdder 4.x leans on this heavily: every auto item texture is
 * remapped to a short {@code ia:<number>} sprite, so a naive "treat the texture ref
 * as a file path" resolver looks for {@code assets/ia/textures/627.png} (which does
 * not exist) and fails for the entire pack.
 *
 * <p>This index walks every {@code .../atlases/*.json} in the merged pack — including
 * the ones ItemsAdder ships inside pack overlays (e.g. {@code ia_overlay_modern_atlas/})
 * — and records {@code sprite -> resource} for each {@code single} source whose sprite
 * name differs from its resource. {@link AssetResolver} consults it to rewrite a
 * resolved model's {@code textures} block back to real texture locations before any
 * Bedrock stitching / icon / geometry step runs.
 *
 * <p>Only {@code single} sources carry an explicit sprite rename; {@code directory},
 * {@code filter}, {@code unstitch}, and {@code paletted_permutations} sources derive
 * sprite names from real file paths and need no remap, so they are ignored here.
 */
public final class AtlasSpriteIndex {

    private final Map<String, String> spriteToResource;

    private AtlasSpriteIndex(Map<String, String> spriteToResource) {
        this.spriteToResource = spriteToResource;
    }

    /**
     * Walks {@code packRoot} for atlas JSON files and builds the sprite-remap index.
     * Never throws: a malformed atlas (or an unreadable pack) yields an empty/partial
     * index and the caller transparently falls back to direct path resolution.
     */
    public static AtlasSpriteIndex build(File packRoot) {
        Map<String, String> map = new HashMap<>();
        if (packRoot == null || !packRoot.isDirectory()) {
            return new AtlasSpriteIndex(map);
        }
        try (Stream<Path> walk = Files.walk(packRoot.toPath())) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        Path parent = p.getParent();
                        return parent != null
                                && "atlases".equals(parent.getFileName().toString())
                                && p.getFileName().toString().endsWith(".json");
                    })
                    .forEach(p -> parseAtlas(p.toFile(), packRoot, map));
        } catch (Exception e) {
            BedrockLog.warn("[BedrockConverter] Failed to scan atlas files for sprite remaps: " + e.getMessage());
        }
        if (!map.isEmpty()) {
            BedrockLog.debug("[BedrockConverter] Atlas sprite index: " + map.size() + " sprite remap(s) found.");
        }
        return new AtlasSpriteIndex(map);
    }

    private static void parseAtlas(File atlasFile, File packRoot, Map<String, String> map) {
        try (FileReader reader = new FileReader(atlasFile, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) return;
            JsonObject obj = parsed.getAsJsonObject();
            if (!obj.has("sources") || !obj.get("sources").isJsonArray()) return;
            JsonArray sources = obj.getAsJsonArray("sources");
            for (JsonElement el : sources) {
                if (!el.isJsonObject()) continue;
                JsonObject src = el.getAsJsonObject();
                if (!isPrimitiveString(src, "type") || !"single".equals(src.get("type").getAsString())) continue;
                if (!isPrimitiveString(src, "resource")) continue;
                String resource = src.get("resource").getAsString();
                // A single source without an explicit "sprite" keeps the resource as its
                // own sprite name — no remap, nothing to record.
                if (!isPrimitiveString(src, "sprite")) continue;
                String sprite = src.get("sprite").getAsString();
                if (sprite.equals(resource)) continue;
                putPreferringExisting(map, sprite, resource, packRoot);
            }
        } catch (Exception e) {
            BedrockLog.debug("[BedrockConverter] Skipped unreadable atlas " + atlasFile.getPath() + ": " + e.getMessage());
        }
    }

    /**
     * Records {@code sprite -> resource}, but when the same sprite is declared in more
     * than one atlas (ItemsAdder ships a modern and a legacy overlay that can both
     * define it), prefer a resource whose texture file actually exists on disk so the
     * later existence check resolves to a real PNG.
     */
    private static void putPreferringExisting(Map<String, String> map, String sprite, String resource, File packRoot) {
        String existing = map.get(sprite);
        if (existing == null) {
            map.put(sprite, resource);
            return;
        }
        if (existing.equals(resource)) return;
        if (!textureFileExists(existing, packRoot) && textureFileExists(resource, packRoot)) {
            map.put(sprite, resource);
        }
    }

    private static boolean textureFileExists(String resourceRef, File packRoot) {
        int colon = resourceRef.indexOf(':');
        String ns = colon >= 0 ? resourceRef.substring(0, colon) : "minecraft";
        String path = colon >= 0 ? resourceRef.substring(colon + 1) : resourceRef;
        return new File(packRoot, "assets/" + ns + "/textures/" + path + ".png").isFile();
    }

    private static boolean isPrimitiveString(JsonObject obj, String key) {
        return obj.has(key) && obj.get(key).isJsonPrimitive();
    }

    /**
     * Returns the real texture resource a sprite ref maps to, or the ref unchanged when
     * it is not an atlas sprite remap (the common case for non-ItemsAdder packs).
     */
    public String resolve(String textureRef) {
        if (textureRef == null) return null;
        return spriteToResource.getOrDefault(textureRef, textureRef);
    }

    public boolean isEmpty() {
        return spriteToResource.isEmpty();
    }

    public int size() {
        return spriteToResource.size();
    }
}
