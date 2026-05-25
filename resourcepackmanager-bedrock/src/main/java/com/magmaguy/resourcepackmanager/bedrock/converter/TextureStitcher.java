package com.magmaguy.resourcepackmanager.bedrock.converter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.magmaguy.resourcepackmanager.bedrock.BedrockLog;
import com.magmaguy.resourcepackmanager.bedrock.model.SpriteInfo;
import com.magmaguy.resourcepackmanager.bedrock.util.BedrockNaming;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Stitches multiple textures from an FMM-style multi-bone model (or a single
 * generic Java item model) into a single atlas PNG. Bedrock geometry only
 * supports one texture per geometry, so multi-texture models must be combined.
 *
 * <p>Two entry points are provided:
 * <ul>
 *   <li>{@link #stitch(String, List, File, File)} &mdash; the legacy FMM path:
 *       accepts a list of bone JSON files on disk, each representing one bone
 *       of an FMM multi-bone model.</li>
 *   <li>{@link #stitchSingleModel(String, String, JsonObject, File, File)} &mdash;
 *       the generic path: accepts an already-parsed Java item model JSON in
 *       memory, treated as a single &quot;bone&quot;. Used by the generic
 *       Java&rarr;Bedrock pipeline (Phase 6) which has the merged-parent JSON
 *       in hand and does not want to round-trip through disk.</li>
 * </ul>
 *
 * <p>Both paths share the texture-collection / atlas-building / per-bone-icon
 * code via {@link #stitchFromBoneTextures(String, Map, File, File)}. The
 * FMM and generic paths differ only in how they construct the input
 * {@code boneName -&gt; textures-map} mapping.
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
     * <p>
     * {@code bonePrimaryIconPath} maps each boneName to the {@code item_texture.json}
     * texture path (no extension, relative to pack root) to use for that bone's
     * inventory icon. If a bone has no determinable primary texture, it is absent
     * from this map and the caller should fall back to {@link #bedrockTexturePath()}.
     */
    public record StitchResult(
            File atlasFile,
            int atlasWidth,
            int atlasHeight,
            Map<String, SpriteInfo> spriteMap,
            String bedrockTexturePath,
            Map<String, String> bonePrimaryIconPath
    ) {}

    /**
     * Collects all unique textures referenced by bones in a model, stitches them
     * into one atlas, writes it to the Bedrock pack, and returns sprite mapping info.
     */
    public static StitchResult stitch(String modelName, List<File> boneJsonFiles,
                                      File mergedPackRoot, File bedrockPackDir) {
        // 1. Collect unique texture references across all bones (union, first-wins)
        //    Also collect per-bone texture maps so we can pick a primary texture for icons.
        Map<String, BoneTextures> boneTextures = collectBoneTextures(boneJsonFiles);
        // FMM path: one atlas per model directory, shared by all bones. modelName is
        // already unique per FMM model (e.g. "01_em_wolf") so this never collides.
        String atlasPathStem = "textures/entity/" + modelName + "/atlas";
        return stitchFromBoneTextures(modelName, boneTextures, atlasPathStem,
                mergedPackRoot, bedrockPackDir);
    }

    /**
     * Generic-pipeline entry point: stitch a single in-memory Java item model
     * into a Bedrock atlas. The model is treated as a single &quot;bone&quot;
     * named {@code boneName} so the existing per-bone icon machinery still
     * applies. Use {@code modelName = <namespace>} and {@code boneName = <path
     * with slashes replaced by underscores>} to avoid collisions across plugins.
     *
     * <p>This was added in Phase 6 of the generic Java&rarr;Bedrock pipeline so
     * the generic 3D path can reuse all of {@link TextureStitcher}'s atlas
     * building, per-bone icon emission, and sprite-map UV computation without
     * having to write the resolved/merged JSON back to disk.
     *
     * <p>The output atlas path is {@code textures/entity/<modelName>/<boneName>/atlas.png}
     * &mdash; per-(model x bone) scoping is required because in this pipeline
     * {@code modelName} is the plugin namespace (e.g. {@code "elitemobs"}) shared
     * by many independent models. FMM's {@link #stitch(String, List, File, File)}
     * uses {@code textures/entity/<modelName>/atlas.png} because its
     * {@code modelName} is already unique per FMM model directory.
     */
    public static StitchResult stitchSingleModel(String modelName, String boneName,
                                                 JsonObject javaModel,
                                                 File mergedPackRoot, File bedrockPackDir) {
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
                mergedPackRoot, bedrockPackDir);
    }

    /**
     * Shared implementation: given a pre-collected
     * {@code boneName -> textures-slot-map} mapping (one entry per bone for the
     * FMM path, exactly one entry for the generic single-model path), build the
     * atlas, write per-bone icons, and return the stitch result.
     */
    private static StitchResult stitchFromBoneTextures(String modelName,
                                                       Map<String, BoneTextures> boneTextures,
                                                       String atlasPathStem,
                                                       File mergedPackRoot, File bedrockPackDir) {
        Map<String, String> textureMap = unionTextures(boneTextures);
        if (textureMap.isEmpty()) {
            BedrockLog.warn("[BedrockConverter] No textures found for model " + modelName);
            return null;
        }

        // 2. Order slot keys: numeric-ascending first, then lexicographic for the rest.
        //    Strip leading '#' before comparison (Java models may use "#layer0" etc.).
        List<String> sortedIndices = new ArrayList<>(textureMap.keySet());
        sortedIndices.sort(TextureStitcher::compareSlotKeys);

        // 3. Load unique textures (dedup by source texture reference -> one atlas region).
        Map<String, BufferedImage> loadedByRef = new LinkedHashMap<>(); // textureRef -> image
        Map<String, File> sourceFileByRef = new LinkedHashMap<>();      // textureRef -> file on disk
        List<String> placementOrderRefs = new ArrayList<>();            // ref order to draw

        for (String index : sortedIndices) {
            String textureRef = textureMap.get(index);
            if (textureRef == null) continue;
            if (loadedByRef.containsKey(textureRef)) continue; // already loaded once

            String ns = BedrockNaming.extractNamespace(textureRef);
            String path = BedrockNaming.extractPath(textureRef);
            File textureFile = new File(mergedPackRoot, "assets/" + ns + "/textures/" + path + ".png");
            if (!textureFile.exists()) {
                BedrockLog.warn("[BedrockConverter] Texture not found: " + textureFile.getPath());
                continue;
            }

            try {
                BufferedImage img = ImageIO.read(textureFile);
                if (img == null) {
                    BedrockLog.warn("[BedrockConverter] Failed to read image: " + textureFile.getPath());
                    continue;
                }

                // Handle animated textures: if height > width, take first frame from the top.
                if (img.getHeight() > img.getWidth()) {
                    img = img.getSubimage(0, 0, img.getWidth(), img.getWidth());
                }

                loadedByRef.put(textureRef, img);
                sourceFileByRef.put(textureRef, textureFile);
                placementOrderRefs.add(textureRef);
            } catch (IOException e) {
                BedrockLog.warn("[BedrockConverter] Error reading texture " + textureFile.getPath() + ": " + e.getMessage());
            }
        }

        if (loadedByRef.isEmpty()) {
            BedrockLog.warn("[BedrockConverter] No valid textures loaded for model " + modelName);
            return null;
        }

        // 4. Build atlas: row-strip (existing layout; UVs depend on this).
        //    The output atlas stem is supplied by the entry-point so FMM (one atlas
        //    per model dir) and the generic path (one atlas per model x bone) can
        //    each pick a non-colliding scheme. See {@link #stitch} and
        //    {@link #stitchSingleModel}.
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

        // 7. Produce per-bone icon files. For each bone, find its primary texture
        //    (first texture key in iteration order = first slot the bone declares).
        //    Mirrors Rainbow's flat-builtin "icon = the texture itself" pattern
        //    (ModelTextures.java:188-190, 219-221) without needing a 3D renderer.
        Map<String, String> bonePrimaryIconPath = new LinkedHashMap<>();
        for (Map.Entry<String, BoneTextures> e : boneTextures.entrySet()) {
            String boneName = e.getKey();
            String primaryRef = pickPrimaryTextureRef(e.getValue());
            if (primaryRef == null) continue;
            File source = sourceFileByRef.get(primaryRef);
            if (source == null) continue; // missing texture earlier; fall back to atlas

            // Copy primary source PNG to textures/items/<modelName>__<boneName>.png
            // Keep path under textures/items/ (matches Rainbow's idiomatic items path).
            // Java flipbook textures (e.g. EliteMobs' bronzesword.png: 64x768 with a
            // sibling .mcmeta declaring animation) must be cropped to the top frame
            // before emission: Bedrock's flipbook_textures.json does NOT support item
            // icons (only blocks/terrain per docs.microsoft / wiki.bedrock.dev), so a
            // verbatim 64x768 copy renders as a tall squished icon in the inventory.
            String iconRel = "textures/items/" + modelName + "__" + boneName;
            File iconFile = new File(bedrockPackDir, iconRel + ".png");
            try {
                Files.createDirectories(iconFile.getParentFile().toPath());
                writeIconCroppedIfFlipbook(source, iconFile);
                bonePrimaryIconPath.put(boneName, iconRel);
            } catch (IOException ioe) {
                BedrockLog.warn("[BedrockConverter] Failed to write per-bone icon for "
                        + modelName + "/" + boneName + ": " + ioe.getMessage());
            }
        }

        return new StitchResult(outputFile, atlasWidth, atlasHeight, spriteMap,
                bedrockTexturePath, bonePrimaryIconPath);
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
     * Reads each bone JSON and collects its declared {@code textures} block.
     * Preserves insertion order so we can later identify the bone's "primary" slot.
     */
    private static Map<String, BoneTextures> collectBoneTextures(List<File> boneJsonFiles) {
        Map<String, BoneTextures> result = new LinkedHashMap<>();
        for (File file : boneJsonFiles) {
            String boneName = stripExtension(file.getName());
            LinkedHashMap<String, String> textures = new LinkedHashMap<>();
            try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                if (json.has("textures") && json.get("textures").isJsonObject()) {
                    JsonObject texObj = json.getAsJsonObject("textures");
                    for (Map.Entry<String, JsonElement> entry : texObj.entrySet()) {
                        if (entry.getValue().isJsonPrimitive()) {
                            textures.put(entry.getKey(), entry.getValue().getAsString());
                        }
                    }
                }
            } catch (Exception e) {
                BedrockLog.warn("[BedrockConverter] Failed to parse textures from " + file.getPath() + ": " + e.getMessage());
            }
            result.put(boneName, new BoneTextures(textures));
        }
        return result;
    }

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
     * Picks the texture reference used for a bone's inventory icon.
     * Prefers the slot keyed {@code "0"} (FMM convention for "main"), falling back
     * to the first slot in declaration order. Returns {@code null} for textureless bones.
     */
    private static String pickPrimaryTextureRef(BoneTextures bt) {
        LinkedHashMap<String, String> textures = bt.textures();
        if (textures.isEmpty()) return null;
        // Prefer "0" / "#0" / "layer0" if present, otherwise first declared slot.
        for (String preferredKey : new String[]{"0", "#0", "layer0", "#layer0"}) {
            String v = textures.get(preferredKey);
            if (v != null) return v;
        }
        return textures.values().iterator().next();
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

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }
}
