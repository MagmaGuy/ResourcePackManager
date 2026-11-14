package com.magmaguy.resourcepackmanager.bedrock.converter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.magmaguy.resourcepackmanager.bedrock.BedrockLog;
import com.magmaguy.resourcepackmanager.bedrock.model.SpriteInfo;
import com.magmaguy.resourcepackmanager.bedrock.util.BedrockNaming;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Stitches the textures of a generic Java item model into a single atlas PNG.
 * Bedrock geometry only supports one texture per geometry, so multi-texture
 * models must be combined.
 *
 * <p>The entry point is
 * {@link #stitchSingleModel(String, String, JsonObject, File, File)}: it accepts
 * an already-parsed Java item model JSON in memory, treated as a single
 * &quot;bone&quot;. The generic Java&rarr;Bedrock pipeline has the merged-parent
 * JSON in hand and does not round-trip it through disk. That pipeline renders
 * its own compact inventory icon.
 */
public class TextureStitcher {

    /**
     * Rainbow mirrors Mojang's SpriteLoader, which caps atlas dims at 16384 (1 << 14)
     * for GPU upload. Bedrock pack output isn't GPU-bound, but we warn if exceeded
     * since Bedrock clients may reject the atlas. See ModelTextures.java:282.
     */
    static final int MAX_ATLAS_DIM = 1 << 14; // 16384

    /**
     * Result of stitching textures for a model.
     */
    public record StitchResult(
            File atlasFile,
            int atlasWidth,
            int atlasHeight,
            Map<String, SpriteInfo> spriteMap,
            String bedrockTexturePath
    ) {}

    /**
     * Generic-pipeline entry point: stitch a single in-memory Java item model
     * into a Bedrock atlas. The model is treated as a single &quot;bone&quot;
     * named {@code boneName}. Use {@code modelName = <namespace>} and
     * {@code boneName = <path with slashes replaced by underscores>} to avoid
     * collisions across plugins.
     *
     * <p>This was added in Phase 6 of the generic Java&rarr;Bedrock pipeline so
     * the generic 3D path can reuse {@link TextureStitcher}'s atlas building and
     * sprite-map UV computation without having to write the resolved/merged JSON
     * back to disk. The generic path renders a small perspective item icon
     * separately, so this entry point intentionally does not copy full source
     * textures into {@code textures/items/<modelName>__<boneName>.png}.
     *
     * <p>The output atlas path is {@code textures/entity/<modelName>/<boneName>/atlas.png}
     * &mdash; per-(model x bone) scoping is required because in this pipeline
     * {@code modelName} is the plugin namespace (e.g. {@code "elitemobs"}) shared
     * by many independent models.
     */
    public static StitchResult stitchSingleModel(String modelName, String boneName,
                                                 JsonObject javaModel,
                                                 File mergedPackRoot, File bedrockPackDir) {
        return stitchSingleModel(modelName, boneName, javaModel, mergedPackRoot, bedrockPackDir,
                new DecodedTextureCache());
    }

    /**
     * As {@link #stitchSingleModel(String, String, JsonObject, File, File)}, decoding source
     * textures through a cache shared across the conversion run.
     */
    public static StitchResult stitchSingleModel(String modelName, String boneName,
                                                 JsonObject javaModel,
                                                 File mergedPackRoot, File bedrockPackDir,
                                                 DecodedTextureCache decodedTextures) {
        Map<String, BoneTextures> boneTextures = new LinkedHashMap<>();
        LinkedHashMap<String, String> textures = new LinkedHashMap<>();
        if (javaModel != null && javaModel.has("textures") && javaModel.get("textures").isJsonObject()) {
            JsonObject texObj = javaModel.getAsJsonObject("textures");
            for (Map.Entry<String, JsonElement> entry : texObj.entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    textures.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        }
        boneTextures.put(boneName, new BoneTextures(textures));
        // Generic path: modelName is the plugin namespace (e.g. "elitemobs") which
        // is shared across MANY independent models. The atlas filename MUST be
        // scoped per-(model x bone) or every generic 3D model in that namespace
        // would overwrite the same `textures/entity/<ns>/atlas.png`, leaving the
        // wrong dimensions on disk vs the per-model geo's texture_width/_height.
        String atlasPathStem = "textures/entity/" + modelName + "/" + boneName + "/atlas";
        return stitchFromBoneTextures(modelName, boneTextures, atlasPathStem,
                mergedPackRoot, bedrockPackDir, decodedTextures);
    }

    /**
     * Shared implementation: given a pre-collected
     * {@code boneName -> textures-slot-map} mapping (exactly one entry for the
     * generic single-model path), build the atlas and return the stitch result.
     */
    private static StitchResult stitchFromBoneTextures(String modelName,
                                                       Map<String, BoneTextures> boneTextures,
                                                       String atlasPathStem,
                                                       File mergedPackRoot, File bedrockPackDir,
                                                       DecodedTextureCache decodedTextures) {
        Map<String, String> textureMap = unionTextures(boneTextures);
        if (textureMap.isEmpty()) {
            BedrockLog.warn("[BedrockConverter] No textures found for model " + modelName
                    + " (model has no usable 'textures' entries — only a 'particle' slot, or none at all)");
            return null;
        }

        // 2. Order slot keys: numeric-ascending first, then lexicographic for the rest.
        //    Strip leading '#' before comparison (Java models may use "#layer0" etc.).
        List<String> sortedIndices = new ArrayList<>(textureMap.keySet());
        sortedIndices.sort(TextureStitcher::compareSlotKeys);

        // 3. Load unique textures (dedup by source texture reference -> one atlas region).
        Map<String, BufferedImage> loadedByRef = new LinkedHashMap<>(); // textureRef -> image
        Set<String> attemptedRefs = new HashSet<>();
        List<String> placementOrderRefs = new ArrayList<>();            // ref order to draw
        List<String> missingCustom = new ArrayList<>();                 // custom-ns refs with no file on disk
        List<String> missingVanilla = new ArrayList<>();                // minecraft: refs (client-side, expected miss)

        for (String index : sortedIndices) {
            String textureRef = textureMap.get(index);
            if (textureRef == null) continue;
            if (!attemptedRefs.add(textureRef)) continue; // Includes absent and undecodable textures.

            String ns = BedrockNaming.extractNamespace(textureRef);
            String path = BedrockNaming.extractPath(textureRef);
            File textureFile = new File(mergedPackRoot, "assets/" + ns + "/textures/" + path + ".png");
            if (!textureFile.exists()) {
                // Per-texture miss — happens routinely for models that reference
                // optional/variant slot keys that aren't present in this build of
                // the pack. Demoted to debug; the model still emits with its
                // present textures, and a fully-broken model surfaces as the
                // "No valid textures loaded" warn below — which names these paths.
                String entry = textureRef + " -> " + textureFile.getPath();
                if ("minecraft".equals(ns)) missingVanilla.add(entry);
                else missingCustom.add(entry);
                BedrockLog.debug("[BedrockConverter] Texture not found: " + textureFile.getPath());
                continue;
            }

            try {
                BufferedImage img = decodedTextures.read(textureFile);
                if (img == null) {
                    BedrockLog.warn("[BedrockConverter] Failed to read image: " + textureFile.getPath());
                    continue;
                }

                // Handle animated textures: if height > width, take first frame from the top.
                if (img.getHeight() > img.getWidth()) {
                    img = img.getSubimage(0, 0, img.getWidth(), img.getWidth());
                }

                loadedByRef.put(textureRef, img);
                placementOrderRefs.add(textureRef);
            } catch (IOException e) {
                BedrockLog.warn("[BedrockConverter] Error reading texture " + textureFile.getPath() + ": " + e.getMessage());
            }
        }

        if (loadedByRef.isEmpty()) {
            if (!missingCustom.isEmpty()) {
                BedrockLog.warn("[BedrockConverter] No valid textures loaded for model " + modelName
                        + " — none of its " + textureMap.size() + " texture ref(s) resolved to a file on disk. Missing: "
                        + summarizeMissing(missingCustom)
                        + (missingVanilla.isEmpty() ? "" : " (+" + missingVanilla.size() + " vanilla ref(s) not shipped)"));
            } else {
                // Every miss was a vanilla texture the client already has — expected for a
                // vanilla fallback model that slipped into the scan; keep it quiet.
                BedrockLog.debug("[BedrockConverter] No valid textures loaded for model " + modelName
                        + " (all " + missingVanilla.size() + " ref(s) are vanilla textures not shipped in the pack)");
            }
            return null;
        }

        // 4. Build atlas: row-strip (existing layout; UVs depend on this).
        //    The output atlas stem is supplied by the entry point (one atlas per
        //    model x bone). See {@link #stitchSingleModel}.
        String bedrockTexturePath = atlasPathStem;
        File outputFile = new File(bedrockPackDir, bedrockTexturePath + ".png");
        Map<String, SpriteInfo> placedByRef = new LinkedHashMap<>();
        BufferedImage atlas;
        int atlasWidth, atlasHeight;

        if (placementOrderRefs.size() == 1) {
            // Single unique texture — no stitching needed.
            String onlyRef = placementOrderRefs.get(0);
            atlas = loadedByRef.get(onlyRef);
            atlasWidth = atlas.getWidth();
            atlasHeight = atlas.getHeight();
            placedByRef.put(onlyRef, new SpriteInfo(0, 0, atlasWidth, atlasHeight));
        } else {
            atlasHeight = loadedByRef.values().stream().mapToInt(BufferedImage::getHeight).max().orElse(0);
            atlasWidth = loadedByRef.values().stream().mapToInt(BufferedImage::getWidth).sum();

            atlas = new BufferedImage(atlasWidth, atlasHeight, BufferedImage.TYPE_INT_ARGB);
            var g = atlas.createGraphics();
            int xOffset = 0;
            for (String ref : placementOrderRefs) {
                BufferedImage img = loadedByRef.get(ref);
                g.drawImage(img, xOffset, 0, null);
                placedByRef.put(ref, new SpriteInfo(xOffset, 0, img.getWidth(), img.getHeight()));
                xOffset += img.getWidth();
            }
            g.dispose();
        }

        if (atlasWidth > MAX_ATLAS_DIM || atlasHeight > MAX_ATLAS_DIM) {
            BedrockLog.warn("[BedrockConverter] Atlas " + modelName + " exceeds max size ("
                    + atlasWidth + "x" + atlasHeight + "); Bedrock may reject this");
        }

        // 5. Fan placement out across slot keys so geometry-side lookups by slot
        //    key resolve to the right sprite region. Strip '#' from the stored key
        //    so the geometry converter's lookup (which also strips '#') matches.
        Map<String, SpriteInfo> spriteMap = new LinkedHashMap<>();
        for (String index : sortedIndices) {
            String ref = textureMap.get(index);
            SpriteInfo si = placedByRef.get(ref);
            if (si == null) continue;
            spriteMap.put(stripHash(index), si);
        }

        // 6. Write atlas PNG.
        try {
            Files.createDirectories(outputFile.getParentFile().toPath());
            ImageIO.write(atlas, "PNG", outputFile);
        } catch (IOException e) {
            BedrockLog.warn("[BedrockConverter] Failed to write atlas for " + modelName + ": " + e.getMessage());
            return null;
        }

        return new StitchResult(outputFile, atlasWidth, atlasHeight, spriteMap,
                bedrockTexturePath);
    }

    /**
     * Comparator for slot keys. Strips '#' first, then sorts numeric keys ascending
     * before any non-numeric keys (lexicographic among themselves). Never throws on
     * non-numeric input (unlike {@code Comparator.comparingInt(Integer::parseInt)}).
     */
    private static int compareSlotKeys(String a, String b) {
        String ka = stripHash(a);
        String kb = stripHash(b);
        boolean na = isNonNegativeInt(ka);
        boolean nb = isNonNegativeInt(kb);
        if (na && nb) {
            try {
                return Integer.compare(Integer.parseInt(ka), Integer.parseInt(kb));
            } catch (NumberFormatException ex) {
                // overflow: fall through to lexicographic
            }
        }
        if (na != nb) return na ? -1 : 1;
        return ka.compareTo(kb);
    }

    /** Caps a missing-texture list so a single diagnostic warn line stays readable. */
    private static String summarizeMissing(List<String> missing) {
        int cap = 5;
        if (missing.size() <= cap) return String.join(", ", missing);
        return String.join(", ", missing.subList(0, cap)) + " (+" + (missing.size() - cap) + " more)";
    }

    private static boolean isNonNegativeInt(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    private static String stripHash(String key) {
        return (key != null && key.startsWith("#")) ? key.substring(1) : key;
    }

    /** Per-bone texture map preserving declaration order from the bone JSON. */
    private record BoneTextures(LinkedHashMap<String, String> textures) {}

    /**
     * Union across all bone texture maps. First-bone-wins on slot-key conflict,
     * matching the previous behaviour (and Rainbow's slot-map dedup semantics).
     * Mirrors {@code ModelTextures.java:67} by removing the {@code particle} slot.
     */
    private static Map<String, String> unionTextures(Map<String, BoneTextures> boneTextures) {
        Map<String, String> out = new LinkedHashMap<>();
        for (BoneTextures bt : boneTextures.values()) {
            for (Map.Entry<String, String> e : bt.textures().entrySet()) {
                out.putIfAbsent(e.getKey(), e.getValue());
            }
        }
        out.remove("particle");
        out.remove("#particle");
        return out;
    }

    /**
     * Writes the source PNG to {@code dest}. If the source is a vertical flipbook
     * (height &gt; width), only the top {@code width x width} frame is written.
     * Otherwise the file is copied verbatim (faster, preserves any extra PNG
     * metadata the source carries).
     *
     * <p>Java packs encode item-icon animation by stacking N frames vertically and
     * pairing the PNG with a sibling {@code .png.mcmeta}. Bedrock's animation
     * pipeline for item icons is non-existent (Bedrock flipbook_textures.json is
     * documented for blocks/terrain only, see Microsoft Learn and wiki.bedrock.dev
     * "Block Texture Animation"). A non-square icon shows up squished in the
     * Bedrock inventory, so cropping to frame 0 is the visible-icon fallback.
     */
    public static void writeIconCroppedIfFlipbook(File source, File dest) throws IOException {
        BufferedImage img = ImageIO.read(source);
        if (img == null) {
            // Unreadable as image: fall back to verbatim copy so we don't silently drop it.
            Files.copy(source.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        if (img.getHeight() > img.getWidth()) {
            BufferedImage frame = img.getSubimage(0, 0, img.getWidth(), img.getWidth());
            ImageIO.write(frame, "PNG", dest);
        } else {
            Files.copy(source.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
