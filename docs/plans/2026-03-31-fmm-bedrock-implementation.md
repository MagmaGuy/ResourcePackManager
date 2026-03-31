# FMM Bedrock Conversion Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Convert FreeMinecraftModels 3D bone models into Bedrock geometry + attachables + Geyser v2 mappings so Bedrock clients see FMM models via armor stand head slot rendering.

**Architecture:** Scan FMM bone model JSONs from the merged Java pack, stitch multi-texture models into single atlas PNGs, convert Java geometry to Bedrock .geo.json with remapped UVs, generate head-slot attachables with tunable transforms, produce a single Geyser v2 mappings file mapping everything under `minecraft:leather_horse_armor`.

**Tech Stack:** Java 21, Gson, BufferedImage/ImageIO for texture stitching. Bukkit plugin (no test framework — verify via testbed deployment).

**Design doc:** `docs/plans/bedrock-conversion/2026-03-31-fmm-bedrock-conversion-design.md`

---

## Context for Implementer

### FMM Bone Model Structure (example: `assets/freeminecraftmodels/models/01_em_wolf/bodyback.json`)

```json
{
  "textures": {
    "0": "freeminecraftmodels:entity/01_em_wolf/wolf_main",
    "1": "freeminecraftmodels:entity/01_em_wolf/wolf_extra"
  },
  "display": {
    "head": { "translation": [0, -6.4, 0], "scale": [4.0, 4.0, 4.0] }
  },
  "elements": [
    {
      "from": [5.4, 5.4477, 4.4289],
      "to": [10.2, 10.2477, 7.6289],
      "faces": {
        "north": { "uv": [4.14, 1.64, 5.08, 2.58], "texture": "#0", "tintindex": 0 },
        "east":  { "uv": [3.20, 5.55, 3.83, 6.48], "texture": "#0", "tintindex": 0 }
      },
      "rotation": { "origin": [5.8, 8.25, 4.43], "angle": 0.0, "axis": "x" }
    }
  ]
}
```

- FMM pre-scales cube coordinates by `ARMOR_STAND_HEAD_SIZE_MULTIPLIER = 0.4`
- `display.head.scale = [4,4,4]` blows them back up at render time (net 1.6x Blockbench scale)
- Multiple textures per bone are common (wolf: 128x128 + 80x80)
- Bedrock geometry supports only ONE texture per geometry — must stitch
- Face `"texture": "#0"` references index in the textures map

### Bedrock Output Structure

```
ResourcePackManager_Bedrock/
  manifest.json
  pack_icon.png
  textures/entity/{modelName}/atlas.png          (stitched texture)
  models/entity/{modelName}/{boneName}.geo.json   (geometry)
  animations/{modelName}/{boneName}.animation.json (head animation)
  attachables/{modelName}/{boneName}.json          (attachable definition)
```

### Geyser Mappings Output (separate file, not inside pack)

```json
{
  "format_version": 2,
  "items": {
    "minecraft:leather_horse_armor": [
      {
        "type": "definition",
        "model": "freeminecraftmodels:01_em_wolf/bodyback",
        "bedrock_identifier": "freeminecraftmodels:01_em_wolf_bodyback",
        "display_name": "bodyback",
        "bedrock_options": { "icon": "fmm.01_em_wolf.bodyback", "allow_offhand": true }
      }
    ]
  }
}
```

### Key Coordinate Conversion Rules

1. Subtract centre offset `(8, 0, 8)` from `from`/`to`
2. `origin = min(from, to)`, `size = max - origin`
3. Invert X: `origin.x = -(origin.x + size.x)`
4. Zero-thickness → 0.01 minimum
5. Rotation: negate X and Z angles, keep Y
6. Rotation pivot: subtract centre, invert X
7. Bone pivot = centre of all cubes' bounding box (X inverted)
8. Up/down UV faces: flip (origin at max, negative size)

### Files to Keep

- `bedrock/BedrockConversion.java` — rebuild as orchestrator
- `bedrock/GeyserDeployer.java` — keep as-is
- `bedrock/model/BedrockManifest.java` — keep as-is
- `bedrock/util/BedrockNaming.java` — keep as-is
- `bedrock/util/BedrockZip.java` — keep as-is

### Files to Delete

All other classes in `bedrock/converter/` and `bedrock/model/` (ItemConverter, TextureConverter, ModelResolver, ItemTextureAtlasBuilder, GeyserMappingBuilder, GeometryConverter, SoundConverter, LanguageConverter, AnimationConverter, BlockConverter, EquipmentConverter, DiscoveredItem, GeyserItemDefinition, ItemTextureEntry).

---

## Task 1: Delete Old Converter Classes

**Files:**
- Delete: `src/main/java/com/magmaguy/resourcepackmanager/bedrock/converter/ItemConverter.java`
- Delete: `src/main/java/com/magmaguy/resourcepackmanager/bedrock/converter/TextureConverter.java`
- Delete: `src/main/java/com/magmaguy/resourcepackmanager/bedrock/converter/ModelResolver.java`
- Delete: `src/main/java/com/magmaguy/resourcepackmanager/bedrock/converter/ItemTextureAtlasBuilder.java`
- Delete: `src/main/java/com/magmaguy/resourcepackmanager/bedrock/converter/GeyserMappingBuilder.java`
- Delete: `src/main/java/com/magmaguy/resourcepackmanager/bedrock/converter/GeometryConverter.java`
- Delete: `src/main/java/com/magmaguy/resourcepackmanager/bedrock/converter/SoundConverter.java`
- Delete: `src/main/java/com/magmaguy/resourcepackmanager/bedrock/converter/LanguageConverter.java`
- Delete: `src/main/java/com/magmaguy/resourcepackmanager/bedrock/converter/AnimationConverter.java`
- Delete: `src/main/java/com/magmaguy/resourcepackmanager/bedrock/converter/BlockConverter.java`
- Delete: `src/main/java/com/magmaguy/resourcepackmanager/bedrock/converter/EquipmentConverter.java`
- Delete: `src/main/java/com/magmaguy/resourcepackmanager/bedrock/model/DiscoveredItem.java`
- Delete: `src/main/java/com/magmaguy/resourcepackmanager/bedrock/model/GeyserItemDefinition.java`
- Delete: `src/main/java/com/magmaguy/resourcepackmanager/bedrock/model/ItemTextureEntry.java`

**Step 1:** Delete all listed files.

**Step 2:** Verify the project compiles (it won't yet — `BedrockConversion.java` references them). That's OK, we rebuild it in Task 6.

**Step 3:** Commit:
```
git add -u
git commit -m "chore: remove old generic bedrock converters to make way for FMM-focused implementation"
```

---

## Task 2: Create Data Models

**Files:**
- Create: `src/main/java/com/magmaguy/resourcepackmanager/bedrock/model/FmmBoneModel.java`
- Create: `src/main/java/com/magmaguy/resourcepackmanager/bedrock/model/SpriteInfo.java`

### FmmBoneModel.java

Data class for a discovered FMM bone model. Holds everything needed for conversion.

```java
package com.magmaguy.resourcepackmanager.bedrock.model;

import lombok.Data;
import java.io.File;

/**
 * Represents a single FMM bone model discovered in the merged resource pack.
 */
@Data
public class FmmBoneModel {
    /** Model group name, e.g. "01_em_wolf" */
    private final String modelName;
    /** Bone name within the model, e.g. "bodyback" */
    private final String boneName;
    /** The Java model JSON file */
    private final File modelFile;
    /** item_model value FMM sets via setItemModel(), e.g. "freeminecraftmodels:01_em_wolf/bodyback" */
    private final String itemModelKey;
}
```

### SpriteInfo.java

Atlas sprite position data for UV remapping.

```java
package com.magmaguy.resourcepackmanager.bedrock.model;

/**
 * Position and dimensions of a single texture within a stitched atlas.
 */
public record SpriteInfo(int x, int y, int width, int height) {
}
```

**Step 1:** Create both files with the code above.

**Step 2:** Commit:
```
git add src/main/java/com/magmaguy/resourcepackmanager/bedrock/model/FmmBoneModel.java
git add src/main/java/com/magmaguy/resourcepackmanager/bedrock/model/SpriteInfo.java
git commit -m "feat: add FMM bone model and sprite info data classes"
```

---

## Task 3: Create FmmModelScanner

**Files:**
- Create: `src/main/java/com/magmaguy/resourcepackmanager/bedrock/converter/FmmModelScanner.java`

Scans `assets/freeminecraftmodels/models/` for bone model JSONs. Groups them by model name. Skips `display/` subdirectory. Returns a `Map<String, List<FmmBoneModel>>` keyed by model name.

```java
package com.magmaguy.resourcepackmanager.bedrock.converter;

import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.bedrock.model.FmmBoneModel;

import java.io.File;
import java.util.*;

/**
 * Scans the merged resource pack for FreeMinecraftModels bone model files.
 * Looks in assets/freeminecraftmodels/models/{modelName}/{boneName}.json
 * Skips the display/ subdirectory (those are hand-held display models, not bone models).
 */
public class FmmModelScanner {

    private static final String FMM_NAMESPACE = "freeminecraftmodels";

    /**
     * Scans the merged pack for FMM bone models.
     *
     * @param mergedPackRoot root directory of the unzipped merged Java resource pack
     * @return map of modelName -> list of bone models in that model
     */
    public static Map<String, List<FmmBoneModel>> scan(File mergedPackRoot) {
        Map<String, List<FmmBoneModel>> result = new LinkedHashMap<>();

        File modelsDir = new File(mergedPackRoot, "assets/" + FMM_NAMESPACE + "/models");
        if (!modelsDir.isDirectory()) {
            Logger.info("[BedrockConverter] No FMM models directory found at " + modelsDir.getPath());
            return result;
        }

        File[] modelDirs = modelsDir.listFiles(File::isDirectory);
        if (modelDirs == null) return result;

        for (File modelDir : modelDirs) {
            String modelName = modelDir.getName();

            // Skip display/ — those are hand-held display models, not bone models
            if ("display".equals(modelName)) continue;

            File[] boneFiles = modelDir.listFiles((dir, name) -> name.endsWith(".json"));
            if (boneFiles == null || boneFiles.length == 0) continue;

            List<FmmBoneModel> bones = new ArrayList<>();
            for (File boneFile : boneFiles) {
                String boneName = boneFile.getName().replaceAll("\\.json$", "");
                // item_model key matches what FMM sets: freeminecraftmodels:{modelName}/{boneName}
                // But FMM actually uses just the boneName for setModelID on 1.21.4+
                // The item definition is at items/{modelName}/{boneName}.json
                // And the model reference inside is "freeminecraftmodels:{boneName}"
                // However, the item itself is registered as freeminecraftmodels:{modelName}/{boneName}
                String itemModelKey = FMM_NAMESPACE + ":" + modelName + "/" + boneName;
                bones.add(new FmmBoneModel(modelName, boneName, boneFile, itemModelKey));
            }

            if (!bones.isEmpty()) {
                // Sort for deterministic output
                bones.sort(Comparator.comparing(FmmBoneModel::getBoneName));
                result.put(modelName, bones);
            }
        }

        int totalBones = result.values().stream().mapToInt(List::size).sum();
        Logger.info("[BedrockConverter] Found " + result.size() + " FMM models with " + totalBones + " total bones.");
        return result;
    }
}
```

**IMPORTANT — item_model key accuracy:** The `itemModelKey` must EXACTLY match what FMM sets via `setItemModel()`. Check `ModelsFolder.java` line 342: `boneBlueprint.setModelID(boneBlueprint.getBoneName())`. And `boneName` comes from the outliner. Then in `ModelArmorStand.java` line 42: `itemMeta.setItemModel(NamespacedKey.fromString(bone.getBoneBlueprint().getModelID()))`. So the key is whatever `getBoneName()` returns, parsed as a NamespacedKey. You MUST verify this against actual testbed item definitions in `assets/freeminecraftmodels/items/{modelName}/{boneName}.json` — read the `model.model` field to see the exact model reference FMM generates. If it's `"freeminecraftmodels:{boneName}"` (without modelName prefix), adjust `itemModelKey` accordingly.

**Step 1:** Create the file.

**Step 2:** Before committing, read a few real FMM item definition files from the testbed to verify the item_model key format:
```
cat BetterStructures/testbed/plugins/FreeMinecraftModels/output/FreeMinecraftModels/assets/freeminecraftmodels/items/01_em_wolf/bodyback.json
```
Compare the `model.model` field against `itemModelKey` and fix if needed.

**Step 3:** Commit:
```
git add src/main/java/com/magmaguy/resourcepackmanager/bedrock/converter/FmmModelScanner.java
git commit -m "feat: add FMM model scanner for bone model discovery"
```

---

## Task 4: Create TextureStitcher

**Files:**
- Create: `src/main/java/com/magmaguy/resourcepackmanager/bedrock/converter/TextureStitcher.java`

Stitches multiple textures into a single horizontal atlas. Returns sprite map for UV remapping.

**Key facts:**
- FMM textures can be different sizes (e.g., wolf: 128x128 + 80x80)
- Single-texture models skip stitching (just copy the PNG)
- Atlas height = max height of all textures
- Animated textures (.mcmeta): extract first frame (height == width for first frame)

```java
package com.magmaguy.resourcepackmanager.bedrock.converter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.bedrock.model.SpriteInfo;
import com.magmaguy.resourcepackmanager.bedrock.util.BedrockNaming;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Stitches multiple textures from an FMM model into a single atlas PNG.
 * Bedrock geometry only supports one texture per geometry, so multi-texture
 * models must be combined.
 */
public class TextureStitcher {

    /**
     * Result of stitching textures for a model.
     *
     * @param atlasFile     the written atlas PNG file in the Bedrock pack
     * @param atlasWidth    total atlas width in pixels
     * @param atlasHeight   total atlas height in pixels
     * @param spriteMap     map of texture index (as string "0", "1", etc.) to sprite position
     * @param bedrockTexturePath Bedrock-relative path without extension, e.g. "textures/entity/01_em_wolf/atlas"
     */
    public record StitchResult(
            File atlasFile,
            int atlasWidth,
            int atlasHeight,
            Map<String, SpriteInfo> spriteMap,
            String bedrockTexturePath
    ) {}

    /**
     * Collects all unique textures referenced by bones in a model, stitches them
     * into one atlas, writes it to the Bedrock pack, and returns sprite mapping info.
     *
     * @param modelName      the model name (e.g., "01_em_wolf")
     * @param boneJsonFiles  all bone JSON files for this model
     * @param mergedPackRoot root of the merged Java resource pack
     * @param bedrockPackDir root of the Bedrock pack being built
     * @return StitchResult, or null on failure
     */
    public static StitchResult stitch(String modelName, List<File> boneJsonFiles,
                                      File mergedPackRoot, File bedrockPackDir) {
        // 1. Collect unique texture references across all bones, preserving index order
        Map<String, String> textureMap = collectTextures(boneJsonFiles);
        if (textureMap.isEmpty()) {
            Logger.warn("[BedrockConverter] No textures found for model " + modelName);
            return null;
        }

        // 2. Load texture images, keyed by index
        List<String> sortedIndices = new ArrayList<>(textureMap.keySet());
        sortedIndices.sort(Comparator.comparingInt(Integer::parseInt));

        List<BufferedImage> images = new ArrayList<>();
        List<String> indices = new ArrayList<>();
        for (String index : sortedIndices) {
            String textureRef = textureMap.get(index);
            String ns = BedrockNaming.extractNamespace(textureRef);
            String path = BedrockNaming.extractPath(textureRef);

            File textureFile = new File(mergedPackRoot, "assets/" + ns + "/textures/" + path + ".png");
            if (!textureFile.exists()) {
                Logger.warn("[BedrockConverter] Texture not found: " + textureFile.getPath());
                continue;
            }

            try {
                BufferedImage img = ImageIO.read(textureFile);
                if (img == null) {
                    Logger.warn("[BedrockConverter] Failed to read image: " + textureFile.getPath());
                    continue;
                }

                // Handle animated textures: if height > width, assume sprite sheet, take first frame
                if (img.getHeight() > img.getWidth()) {
                    img = img.getSubimage(0, 0, img.getWidth(), img.getWidth());
                }

                images.add(img);
                indices.add(index);
            } catch (IOException e) {
                Logger.warn("[BedrockConverter] Error reading texture " + textureFile.getPath() + ": " + e.getMessage());
            }
        }

        if (images.isEmpty()) {
            Logger.warn("[BedrockConverter] No valid textures loaded for model " + modelName);
            return null;
        }

        // 3. Build atlas
        String bedrockTexturePath = "textures/entity/" + modelName + "/atlas";
        File outputFile = new File(bedrockPackDir, bedrockTexturePath + ".png");
        Map<String, SpriteInfo> spriteMap = new LinkedHashMap<>();

        BufferedImage atlas;
        int atlasWidth, atlasHeight;

        if (images.size() == 1) {
            // Single texture — no stitching needed
            atlas = images.get(0);
            atlasWidth = atlas.getWidth();
            atlasHeight = atlas.getHeight();
            spriteMap.put(indices.get(0), new SpriteInfo(0, 0, atlasWidth, atlasHeight));
        } else {
            // Multiple textures — stitch horizontally
            atlasHeight = images.stream().mapToInt(BufferedImage::getHeight).max().orElse(0);
            atlasWidth = images.stream().mapToInt(BufferedImage::getWidth).sum();

            atlas = new BufferedImage(atlasWidth, atlasHeight, BufferedImage.TYPE_INT_ARGB);
            var g = atlas.createGraphics();

            int xOffset = 0;
            for (int i = 0; i < images.size(); i++) {
                BufferedImage img = images.get(i);
                g.drawImage(img, xOffset, 0, null);
                spriteMap.put(indices.get(i), new SpriteInfo(xOffset, 0, img.getWidth(), img.getHeight()));
                xOffset += img.getWidth();
            }
            g.dispose();
        }

        // 4. Write atlas
        try {
            Files.createDirectories(outputFile.getParentFile().toPath());
            ImageIO.write(atlas, "PNG", outputFile);
        } catch (IOException e) {
            Logger.warn("[BedrockConverter] Failed to write atlas for " + modelName + ": " + e.getMessage());
            return null;
        }

        return new StitchResult(outputFile, atlasWidth, atlasHeight, spriteMap, bedrockTexturePath);
    }

    /**
     * Scans all bone JSON files and collects the union of texture references.
     * Returns map of textureIndex -> textureRef (e.g., "0" -> "freeminecraftmodels:entity/wolf/wolf_main").
     */
    private static Map<String, String> collectTextures(List<File> boneJsonFiles) {
        Map<String, String> textures = new LinkedHashMap<>();
        for (File file : boneJsonFiles) {
            try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                if (!json.has("textures")) continue;
                JsonObject texObj = json.getAsJsonObject("textures");
                for (Map.Entry<String, JsonElement> entry : texObj.entrySet()) {
                    textures.putIfAbsent(entry.getKey(), entry.getValue().getAsString());
                }
            } catch (Exception e) {
                Logger.warn("[BedrockConverter] Failed to parse textures from " + file.getPath() + ": " + e.getMessage());
            }
        }
        return textures;
    }
}
```

**Step 1:** Create the file.

**Step 2:** Commit:
```
git add src/main/java/com/magmaguy/resourcepackmanager/bedrock/converter/TextureStitcher.java
git commit -m "feat: add texture stitcher for FMM multi-texture atlas generation"
```

---

## Task 5: Create FmmGeometryConverter

**Files:**
- Create: `src/main/java/com/magmaguy/resourcepackmanager/bedrock/converter/FmmGeometryConverter.java`

Converts a single FMM bone model JSON to a Bedrock .geo.json file.

The converter must:
1. Parse elements from the Java JSON
2. Apply coordinate conversion (centre offset, X inversion)
3. Remap UVs using the sprite map from TextureStitcher
4. Generate a single-bone geometry with binding
5. Write the .geo.json file

```java
package com.magmaguy.resourcepackmanager.bedrock.converter;

import com.google.gson.*;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.bedrock.model.SpriteInfo;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

/**
 * Converts a single FMM bone model (Java Edition JSON) to Bedrock geometry (.geo.json).
 * Applies coordinate conversion following GeyserMC Rainbow's approach and remaps UVs
 * for stitched texture atlases.
 */
public class FmmGeometryConverter {

    private static final double CENTRE_X = 8.0;
    private static final double CENTRE_Y = 0.0;
    private static final double CENTRE_Z = 8.0;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /**
     * Converts a bone model and writes the .geo.json file.
     *
     * @param modelName    model group name (e.g., "01_em_wolf")
     * @param boneName     bone name (e.g., "bodyback")
     * @param javaModel    parsed Java model JSON
     * @param spriteMap    texture index -> atlas sprite info (from TextureStitcher)
     * @param atlasWidth   total atlas width in pixels
     * @param atlasHeight  total atlas height in pixels
     * @param bedrockPackDir root of Bedrock pack being built
     * @return geometry ID (e.g., "geometry.freeminecraftmodels.01_em_wolf.bodyback"), or null on failure
     */
    public static String convert(String modelName, String boneName, JsonObject javaModel,
                                 Map<String, SpriteInfo> spriteMap, int atlasWidth, int atlasHeight,
                                 File bedrockPackDir) {
        if (!javaModel.has("elements") || !javaModel.get("elements").isJsonArray()) {
            Logger.warn("[BedrockConverter] No elements in bone model " + modelName + "/" + boneName);
            return null;
        }

        JsonArray elements = javaModel.getAsJsonArray("elements");
        if (elements.isEmpty()) return null;

        String geometryId = "geometry.freeminecraftmodels." + modelName + "." + boneName;

        // Convert elements to cubes, tracking bounding box for pivot
        JsonArray cubes = new JsonArray();
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;

        for (JsonElement el : elements) {
            if (!el.isJsonObject()) continue;
            JsonObject element = el.getAsJsonObject();
            if (!element.has("from") || !element.has("to")) continue;

            JsonObject cube = convertElement(element, spriteMap, atlasWidth, atlasHeight);
            if (cube == null) continue;
            cubes.add(cube);

            // Track bounding box in centred coordinates
            JsonArray from = element.getAsJsonArray("from");
            JsonArray to = element.getAsJsonArray("to");
            double fx = from.get(0).getAsDouble() - CENTRE_X;
            double fy = from.get(1).getAsDouble() - CENTRE_Y;
            double fz = from.get(2).getAsDouble() - CENTRE_Z;
            double tx = to.get(0).getAsDouble() - CENTRE_X;
            double ty = to.get(1).getAsDouble() - CENTRE_Y;
            double tz = to.get(2).getAsDouble() - CENTRE_Z;
            minX = Math.min(minX, Math.min(fx, tx));
            minY = Math.min(minY, Math.min(fy, ty));
            minZ = Math.min(minZ, Math.min(fz, tz));
            maxX = Math.max(maxX, Math.max(fx, tx));
            maxY = Math.max(maxY, Math.max(fy, ty));
            maxZ = Math.max(maxZ, Math.max(fz, tz));
        }

        if (cubes.isEmpty()) return null;

        // Bone pivot = center of bounding box, X inverted
        double pivotX = -((minX + maxX) / 2.0);
        double pivotY = (minY + maxY) / 2.0;
        double pivotZ = (minZ + maxZ) / 2.0;

        // Build bone
        JsonObject bone = new JsonObject();
        bone.addProperty("name", "bone");
        bone.add("pivot", toArray(pivotX, pivotY, pivotZ));
        bone.addProperty("binding", "q.item_slot_to_bone_name(context.item_slot)");
        bone.add("cubes", cubes);

        JsonArray bones = new JsonArray();
        bones.add(bone);

        // Build description
        JsonObject description = new JsonObject();
        description.addProperty("identifier", geometryId);
        description.addProperty("texture_width", atlasWidth);
        description.addProperty("texture_height", atlasHeight);
        description.addProperty("visible_bounds_width", 4.0);
        description.addProperty("visible_bounds_height", 4.0);
        description.add("visible_bounds_offset", toArray(0, 0.75, 0));

        // Build geometry
        JsonObject geoEntry = new JsonObject();
        geoEntry.add("description", description);
        geoEntry.add("bones", bones);

        JsonArray geoArray = new JsonArray();
        geoArray.add(geoEntry);

        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.16.0");
        root.add("minecraft:geometry", geoArray);

        // Write file
        File geoFile = new File(bedrockPackDir, "models/entity/" + modelName + "/" + boneName + ".geo.json");
        try {
            Files.createDirectories(geoFile.getParentFile().toPath());
            try (FileWriter writer = new FileWriter(geoFile, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            return geometryId;
        } catch (IOException e) {
            Logger.warn("[BedrockConverter] Failed to write geometry " + geoFile.getPath() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Converts a single Java element to a Bedrock cube.
     */
    private static JsonObject convertElement(JsonObject element, Map<String, SpriteInfo> spriteMap,
                                             int atlasWidth, int atlasHeight) {
        JsonArray from = element.getAsJsonArray("from");
        JsonArray to = element.getAsJsonArray("to");

        double fromX = from.get(0).getAsDouble() - CENTRE_X;
        double fromY = from.get(1).getAsDouble() - CENTRE_Y;
        double fromZ = from.get(2).getAsDouble() - CENTRE_Z;
        double toX = to.get(0).getAsDouble() - CENTRE_X;
        double toY = to.get(1).getAsDouble() - CENTRE_Y;
        double toZ = to.get(2).getAsDouble() - CENTRE_Z;

        double originX = Math.min(fromX, toX);
        double originY = Math.min(fromY, toY);
        double originZ = Math.min(fromZ, toZ);
        double sizeX = Math.max(fromX, toX) - originX;
        double sizeY = Math.max(fromY, toY) - originY;
        double sizeZ = Math.max(fromZ, toZ) - originZ;

        // Minimum thickness for zero-thickness planes
        if (sizeX == 0) sizeX = 0.01;
        if (sizeY == 0) sizeY = 0.01;
        if (sizeZ == 0) sizeZ = 0.01;

        // Invert X axis
        originX = -(originX + sizeX);

        JsonObject cube = new JsonObject();
        cube.add("origin", toArray(originX, originY, originZ));
        cube.add("size", toArray(sizeX, sizeY, sizeZ));

        // Rotation
        if (element.has("rotation") && element.get("rotation").isJsonObject()) {
            JsonObject javaRot = element.getAsJsonObject("rotation");
            double angle = javaRot.has("angle") ? javaRot.get("angle").getAsDouble() : 0;
            String axis = javaRot.has("axis") ? javaRot.get("axis").getAsString() : "y";

            if (angle != 0) {
                // Pivot
                if (javaRot.has("origin") && javaRot.get("origin").isJsonArray()) {
                    JsonArray javaOrigin = javaRot.getAsJsonArray("origin");
                    double px = javaOrigin.get(0).getAsDouble() - CENTRE_X;
                    double py = javaOrigin.get(1).getAsDouble() - CENTRE_Y;
                    double pz = javaOrigin.get(2).getAsDouble() - CENTRE_Z;
                    px = -px; // Invert X
                    cube.add("pivot", toArray(px, py, pz));
                }

                // Rotation with X and Z inverted
                JsonArray rotation = new JsonArray();
                switch (axis) {
                    case "x" -> { rotation.add(-angle); rotation.add(0); rotation.add(0); }
                    case "y" -> { rotation.add(0); rotation.add(angle); rotation.add(0); }
                    case "z" -> { rotation.add(0); rotation.add(0); rotation.add(-angle); }
                    default  -> { rotation.add(0); rotation.add(0); rotation.add(0); }
                }
                cube.add("rotation", rotation);
            }
        }

        // UV faces
        if (element.has("faces") && element.get("faces").isJsonObject()) {
            JsonObject javaFaces = element.getAsJsonObject("faces");
            JsonObject bedrockUV = new JsonObject();

            for (String faceName : new String[]{"north", "east", "south", "west", "up", "down"}) {
                if (!javaFaces.has(faceName) || !javaFaces.get(faceName).isJsonObject()) continue;
                JsonObject javaFace = javaFaces.getAsJsonObject(faceName);
                if (!javaFace.has("uv") || !javaFace.get("uv").isJsonArray()) continue;

                // Determine which texture this face uses
                String texRef = javaFace.has("texture") ? javaFace.get("texture").getAsString() : "#0";
                String texIndex = texRef.startsWith("#") ? texRef.substring(1) : texRef;

                boolean isUpDown = faceName.equals("up") || faceName.equals("down");
                JsonObject faceUV = convertFaceUV(
                        javaFace.getAsJsonArray("uv"), isUpDown,
                        spriteMap.get(texIndex), atlasWidth, atlasHeight
                );
                if (faceUV != null) bedrockUV.add(faceName, faceUV);
            }

            if (bedrockUV.size() > 0) {
                cube.add("uv", bedrockUV);
            }
        }

        return cube;
    }

    /**
     * Converts Java UV [u1, v1, u2, v2] (in 0-16 range) to Bedrock per-face UV
     * with atlas remapping.
     *
     * Java UVs are in [0, 16] range regardless of texture size.
     * We scale them to the sprite's pixel dimensions and offset by the sprite's
     * position in the atlas.
     *
     * For up/down faces: UV is flipped (origin at max, negative size).
     */
    private static JsonObject convertFaceUV(JsonArray javaUV, boolean flipUpDown,
                                            SpriteInfo sprite, int atlasWidth, int atlasHeight) {
        if (javaUV.size() < 4) return null;

        double u1 = javaUV.get(0).getAsDouble();
        double v1 = javaUV.get(1).getAsDouble();
        double u2 = javaUV.get(2).getAsDouble();
        double v2 = javaUV.get(3).getAsDouble();

        // Scale from [0, 16] to sprite pixel coordinates
        double scaleX = 1.0;
        double scaleY = 1.0;
        double offsetX = 0;
        double offsetY = 0;

        if (sprite != null) {
            scaleX = sprite.width() / 16.0;
            scaleY = sprite.height() / 16.0;
            offsetX = sprite.x();
            offsetY = sprite.y();
        }

        u1 = u1 * scaleX + offsetX;
        v1 = v1 * scaleY + offsetY;
        u2 = u2 * scaleX + offsetX;
        v2 = v2 * scaleY + offsetY;

        JsonObject faceUV = new JsonObject();
        JsonArray uv = new JsonArray();
        JsonArray uvSize = new JsonArray();

        if (flipUpDown) {
            uv.add(u2);
            uv.add(v2);
            uvSize.add(u1 - u2);
            uvSize.add(v1 - v2);
        } else {
            uv.add(u1);
            uv.add(v1);
            uvSize.add(u2 - u1);
            uvSize.add(v2 - v1);
        }

        faceUV.add("uv", uv);
        faceUV.add("uv_size", uvSize);
        return faceUV;
    }

    private static JsonArray toArray(double x, double y, double z) {
        JsonArray arr = new JsonArray();
        arr.add(round4(x));
        arr.add(round4(y));
        arr.add(round4(z));
        return arr;
    }

    private static double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
```

**Step 1:** Create the file.

**Step 2:** Commit:
```
git add src/main/java/com/magmaguy/resourcepackmanager/bedrock/converter/FmmGeometryConverter.java
git commit -m "feat: add FMM geometry converter (Java bone model to Bedrock .geo.json)"
```

---

## Task 6: Create FmmAttachableGenerator

**Files:**
- Create: `src/main/java/com/magmaguy/resourcepackmanager/bedrock/converter/FmmAttachableGenerator.java`

Generates an attachable definition + head animation per bone. Transform values are centralised at the top of the class for easy tuning.

**Critical note on transforms:** FMM sets `display.head` with `translation: [0, -6.4, 0]` and `scale: [4, 4, 4]`. These are raw values NOT processed through Java's model loader (no `/0.0625` factor). The animation must replicate this effect on Bedrock's head bone binding. Initial values are best-guesses that WILL need tuning via testbed.

```java
package com.magmaguy.resourcepackmanager.bedrock.converter;

import com.google.gson.*;
import com.magmaguy.magmacore.util.Logger;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * Generates Bedrock attachable definitions and head-slot animations for FMM bone models.
 * Transform values are centralised at the top for easy tuning.
 */
public class FmmAttachableGenerator {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    // ========================================================================
    // HEAD SLOT TRANSFORM VALUES — TUNE THESE AGAINST TESTBED
    // ========================================================================
    // These values position the 3D model correctly when worn on an armor stand head.
    // FMM display.head: translation=[0, -6.4, 0], scale=[4, 4, 4]
    //
    // Rainbow reference (for regular items, NOT FMM):
    //   position = translation / 0.0625 * (-0.655, 0.655, 0.655) + (0, 20.0, 0)
    //   rotation = (-rotX, -rotY, rotZ)
    //   scale = javaScale * 0.655
    //
    // java2bedrock.sh reference: position=[0, 19.9, 0], scale=0.625
    //
    // FMM cubes are pre-scaled by 0.4. display.head.scale=4 blows them back up.
    // We need to find the equivalent Bedrock values empirically.
    // Starting point: java2bedrock.sh values adjusted for FMM's 4x display scale.

    private static final double HEAD_POS_X = 0.0;
    private static final double HEAD_POS_Y = 19.5;
    private static final double HEAD_POS_Z = 0.0;
    private static final double HEAD_ROT_X = 0.0;
    private static final double HEAD_ROT_Y = 0.0;
    private static final double HEAD_ROT_Z = 0.0;
    private static final double HEAD_SCALE_X = 2.5;
    private static final double HEAD_SCALE_Y = 2.5;
    private static final double HEAD_SCALE_Z = 2.5;
    // ========================================================================

    /**
     * Generates an attachable JSON file and companion head animation for one bone.
     *
     * @param modelName          model group name
     * @param boneName           bone name
     * @param geometryId         geometry identifier from FmmGeometryConverter
     * @param bedrockTexturePath atlas texture path (no extension)
     * @param bedrockPackDir     root of Bedrock pack
     * @return the bedrock identifier for use in Geyser mappings, or null on failure
     */
    public static String generate(String modelName, String boneName,
                                  String geometryId, String bedrockTexturePath,
                                  File bedrockPackDir) {
        String bedrockIdentifier = "freeminecraftmodels:" + modelName + "_" + boneName;
        String animationId = "animation.fmm." + modelName + "." + boneName + ".head";

        // Generate animation file
        if (!writeAnimation(modelName, boneName, animationId, bedrockPackDir)) {
            Logger.warn("[BedrockConverter] Failed to write animation for " + modelName + "/" + boneName);
            return null;
        }

        // Generate attachable
        if (!writeAttachable(bedrockIdentifier, geometryId, bedrockTexturePath,
                animationId, modelName, boneName, bedrockPackDir)) {
            Logger.warn("[BedrockConverter] Failed to write attachable for " + modelName + "/" + boneName);
            return null;
        }

        return bedrockIdentifier;
    }

    private static boolean writeAttachable(String identifier, String geometryId,
                                            String texturePath, String animationId,
                                            String modelName, String boneName,
                                            File bedrockPackDir) {
        JsonObject description = new JsonObject();
        description.addProperty("identifier", identifier);

        // Materials
        JsonObject materials = new JsonObject();
        materials.addProperty("default", "entity");
        materials.addProperty("enchanted", "entity_alphatest_glint");
        description.add("materials", materials);

        // Textures
        JsonObject textures = new JsonObject();
        textures.addProperty("default", texturePath);
        textures.addProperty("enchanted", "textures/misc/enchanted_item_glint");
        description.add("textures", textures);

        // Geometry
        JsonObject geometry = new JsonObject();
        geometry.addProperty("default", geometryId);
        description.add("geometry", geometry);

        // Scripts
        JsonObject scripts = new JsonObject();
        JsonArray preAnim = new JsonArray();
        preAnim.add("v.head = c.item_slot == 'head';");
        scripts.add("pre_animation", preAnim);

        JsonArray animate = new JsonArray();
        JsonObject headCondition = new JsonObject();
        headCondition.addProperty("head", "v.head");
        animate.add(headCondition);
        scripts.add("animate", animate);
        description.add("scripts", scripts);

        // Animations
        JsonObject animations = new JsonObject();
        animations.addProperty("head", animationId);
        description.add("animations", animations);

        // Render controllers
        JsonArray renderControllers = new JsonArray();
        renderControllers.add("controller.render.item_default");
        description.add("render_controllers", renderControllers);

        // Build root
        JsonObject attachable = new JsonObject();
        attachable.add("description", description);

        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.10.0");
        root.add("minecraft:attachable", attachable);

        // Write
        File outputFile = new File(bedrockPackDir, "attachables/" + modelName + "/" + boneName + ".json");
        return writeJson(outputFile, root);
    }

    private static boolean writeAnimation(String modelName, String boneName,
                                           String animationId, File bedrockPackDir) {
        // Bone transform for head slot
        JsonObject boneTransform = new JsonObject();
        boneTransform.add("position", toArray(HEAD_POS_X, HEAD_POS_Y, HEAD_POS_Z));
        boneTransform.add("rotation", toArray(HEAD_ROT_X, HEAD_ROT_Y, HEAD_ROT_Z));
        boneTransform.add("scale", toArray(HEAD_SCALE_X, HEAD_SCALE_Y, HEAD_SCALE_Z));

        JsonObject bones = new JsonObject();
        bones.add("bone", boneTransform);

        JsonObject animEntry = new JsonObject();
        animEntry.addProperty("loop", true);
        animEntry.add("bones", bones);

        JsonObject animations = new JsonObject();
        animations.add(animationId, animEntry);

        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.8.0");
        root.add("animations", animations);

        File animFile = new File(bedrockPackDir, "animations/" + modelName + "/" + boneName + ".animation.json");
        return writeJson(animFile, root);
    }

    private static boolean writeJson(File file, JsonObject json) {
        try {
            Files.createDirectories(file.getParentFile().toPath());
            try (FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(json, writer);
            }
            return true;
        } catch (IOException e) {
            Logger.warn("[BedrockConverter] Failed to write " + file.getPath() + ": " + e.getMessage());
            return false;
        }
    }

    private static JsonArray toArray(double x, double y, double z) {
        JsonArray arr = new JsonArray();
        arr.add(x);
        arr.add(y);
        arr.add(z);
        return arr;
    }
}
```

**Step 1:** Create the file.

**Step 2:** Commit:
```
git add src/main/java/com/magmaguy/resourcepackmanager/bedrock/converter/FmmAttachableGenerator.java
git commit -m "feat: add FMM attachable generator with tunable head-slot transforms"
```

---

## Task 7: Create FmmGeyserMappingBuilder

**Files:**
- Create: `src/main/java/com/magmaguy/resourcepackmanager/bedrock/converter/FmmGeyserMappingBuilder.java`

Generates a single Geyser v2 custom item mappings JSON. All items map under `minecraft:leather_horse_armor` with `type: "definition"` and a `model` field matching FMM's `setItemModel()` value.

```java
package com.magmaguy.resourcepackmanager.bedrock.converter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.magmaguy.magmacore.util.Logger;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Generates Geyser v2 custom item mappings for FMM bone models.
 * All items are registered under minecraft:leather_horse_armor using the
 * definition type with model field matching FMM's setItemModel() value.
 */
public class FmmGeyserMappingBuilder {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /**
     * Data for a single bone's Geyser mapping.
     */
    public record BoneMapping(
            /** Must match FMM's setItemModel() exactly */
            String itemModelKey,
            /** Bedrock identifier from FmmAttachableGenerator */
            String bedrockIdentifier,
            /** Human-readable name */
            String displayName,
            /** Icon key for item_texture.json (not strictly needed for 3D items but Geyser wants it) */
            String iconKey
    ) {}

    /**
     * Generates the Geyser mappings JSON file.
     *
     * @param boneMappings list of all bone mappings to include
     * @param outputFile   the file to write (e.g., output/rspm_geyser_mappings.json)
     */
    public static void generate(List<BoneMapping> boneMappings, File outputFile) {
        List<Map<String, Object>> definitions = new ArrayList<>();

        for (BoneMapping mapping : boneMappings) {
            Map<String, Object> def = new LinkedHashMap<>();
            def.put("type", "definition");
            def.put("model", mapping.itemModelKey());
            def.put("bedrock_identifier", mapping.bedrockIdentifier());
            def.put("display_name", mapping.displayName());

            Map<String, Object> options = new LinkedHashMap<>();
            options.put("icon", mapping.iconKey());
            options.put("allow_offhand", true);
            def.put("bedrock_options", options);

            definitions.add(def);
        }

        Map<String, Object> items = new LinkedHashMap<>();
        items.put("minecraft:leather_horse_armor", definitions);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format_version", 2);
        root.put("items", items);

        try {
            if (outputFile.getParentFile() != null) {
                Files.createDirectories(outputFile.getParentFile().toPath());
            }
            try (FileWriter writer = new FileWriter(outputFile, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            Logger.info("[BedrockConverter] Generated Geyser mappings with " + boneMappings.size()
                    + " definitions: " + outputFile.getAbsolutePath());
        } catch (IOException e) {
            Logger.warn("[BedrockConverter] Failed to write Geyser mappings: " + e.getMessage());
        }
    }
}
```

**Step 1:** Create the file.

**Step 2:** Commit:
```
git add src/main/java/com/magmaguy/resourcepackmanager/bedrock/converter/FmmGeyserMappingBuilder.java
git commit -m "feat: add Geyser v2 mapping builder for FMM bone models"
```

---

## Task 8: Rebuild BedrockConversion Orchestrator

**Files:**
- Modify: `src/main/java/com/magmaguy/resourcepackmanager/bedrock/BedrockConversion.java`

Gut and rebuild to use the new FMM pipeline. Keep `deployPreviousIfNeeded()` and `recursivelyDelete()`.

Replace the entire `generate()` method body with:

```java
public static void generate(File mergedJavaPack, File outputDir) {
    try {
        Logger.info("Starting FMM Bedrock resource pack conversion for GeyserMC...");

        // 1. Scan for FMM bone models
        Map<String, List<FmmBoneModel>> modelGroups = FmmModelScanner.scan(mergedJavaPack);
        if (modelGroups.isEmpty()) {
            Logger.info("No FMM models found. Skipping Bedrock conversion.");
            return;
        }

        // 2. Create Bedrock pack directory
        File bedrockDir = new File(outputDir, BEDROCK_PACK_NAME);
        if (bedrockDir.exists()) recursivelyDelete(bedrockDir);
        bedrockDir.mkdirs();

        // Copy pack icon
        copyPackIcon(mergedJavaPack, bedrockDir);

        // 3. Process each model group
        List<FmmGeyserMappingBuilder.BoneMapping> allMappings = new ArrayList<>();
        int totalBones = 0;
        int convertedBones = 0;

        for (Map.Entry<String, List<FmmBoneModel>> entry : modelGroups.entrySet()) {
            String modelName = entry.getKey();
            List<FmmBoneModel> bones = entry.getValue();
            totalBones += bones.size();

            // 3a. Stitch textures for this model
            List<File> boneFiles = bones.stream()
                    .map(FmmBoneModel::getModelFile)
                    .collect(Collectors.toList());
            TextureStitcher.StitchResult stitch = TextureStitcher.stitch(
                    modelName, boneFiles, mergedJavaPack, bedrockDir);
            if (stitch == null) {
                Logger.warn("Failed to stitch textures for model " + modelName + ", skipping.");
                continue;
            }

            // 3b. Convert each bone
            for (FmmBoneModel bone : bones) {
                JsonObject javaModel = parseJsonFile(bone.getModelFile());
                if (javaModel == null) continue;

                // Convert geometry
                String geometryId = FmmGeometryConverter.convert(
                        modelName, bone.getBoneName(), javaModel,
                        stitch.spriteMap(), stitch.atlasWidth(), stitch.atlasHeight(),
                        bedrockDir);
                if (geometryId == null) continue;

                // Generate attachable + animation
                String bedrockId = FmmAttachableGenerator.generate(
                        modelName, bone.getBoneName(),
                        geometryId, stitch.bedrockTexturePath(),
                        bedrockDir);
                if (bedrockId == null) continue;

                // Record mapping
                String iconKey = "fmm." + modelName + "." + bone.getBoneName();
                allMappings.add(new FmmGeyserMappingBuilder.BoneMapping(
                        bone.getItemModelKey(),
                        bedrockId,
                        bone.getBoneName(),
                        iconKey
                ));
                convertedBones++;
            }
        }

        if (allMappings.isEmpty()) {
            Logger.warn("No FMM bones could be converted. Skipping Bedrock pack generation.");
            recursivelyDelete(bedrockDir);
            return;
        }

        // 4. Generate manifest
        String contentHash = String.valueOf(System.currentTimeMillis());
        String pluginVersion = ResourcePackManager.plugin.getDescription().getVersion();
        BedrockManifest.write(bedrockDir, pluginVersion, contentHash);

        // 5. Generate Geyser mappings
        File mappingsFile = new File(outputDir, GEYSER_MAPPINGS_NAME);
        FmmGeyserMappingBuilder.generate(allMappings, mappingsFile);

        // 6. Zip
        File bedrockZip = BedrockZip.zip(bedrockDir, outputDir, BEDROCK_PACK_NAME);
        if (bedrockZip == null) {
            Logger.warn("Failed to zip Bedrock resource pack!");
            return;
        }
        recursivelyDelete(bedrockDir);

        // 7. Deploy to Geyser
        if (DefaultConfig.isBedrockAutoDeployToGeyser()) {
            GeyserDeployer.deploy(bedrockZip, mappingsFile);
        }

        Logger.info("FMM Bedrock conversion complete: " + convertedBones + "/" + totalBones
                + " bones from " + modelGroups.size() + " models.");
        Logger.info("Bedrock pack: " + bedrockZip.getAbsolutePath());
        Logger.info("Geyser mappings: " + mappingsFile.getAbsolutePath());
        Logger.info("Note: Restart server for Geyser to load the new pack.");

    } catch (Exception e) {
        Logger.warn("FMM Bedrock conversion failed: " + e.getMessage());
        e.printStackTrace();
    }
}
```

Update imports to reference the new classes. Remove all old imports. Add a `parseJsonFile` helper method (simple Gson file read). Keep `deployPreviousIfNeeded()`, `copyPackIcon()`, `computeContentHash()` (unused but harmless), and `recursivelyDelete()`.

**Step 1:** Rewrite the file with new imports and generate() body.

**Step 2:** Verify the project compiles:
```
cd ResourcePackManager && mvn compile -q
```

**Step 3:** Fix any compilation errors.

**Step 4:** Commit:
```
git add -A
git commit -m "feat: rebuild BedrockConversion orchestrator for FMM-focused pipeline"
```

---

## Task 9: Verify item_model Key Accuracy

**This is critical.** The Geyser mapping `model` field must EXACTLY match what FMM sets via `setItemModel()`.

**Step 1:** Read real FMM item definition files from the testbed:
```
cat BetterStructures/testbed/plugins/FreeMinecraftModels/output/FreeMinecraftModels/assets/freeminecraftmodels/items/01_em_wolf/bodyback.json
```

**Step 2:** The `model.model` field in that JSON is what FMM registers as the item model. Compare it to what `FmmModelScanner` generates for `itemModelKey`.

Possibilities:
- `"freeminecraftmodels:bodyback"` (just boneName, no modelName prefix)
- `"freeminecraftmodels:01_em_wolf/bodyback"` (with modelName prefix)

**Step 3:** If the format doesn't match, update `FmmModelScanner.java` to produce the correct key.

**Step 4:** Commit any fixes:
```
git add -u
git commit -m "fix: correct item_model key format to match FMM's setItemModel() output"
```

---

## Task 10: Build and Manual Verification

**Step 1:** Full Maven build:
```
cd ResourcePackManager && mvn clean package -q
```

**Step 2:** If build succeeds, inspect the generated JAR to verify all new classes are included.

**Step 3:** To verify the converter logic without a server, you can temporarily add a main method or write a quick test that:
- Points at the testbed's merged pack directory
- Runs `FmmModelScanner.scan()`
- Runs `TextureStitcher.stitch()` on one model
- Runs `FmmGeometryConverter.convert()` on one bone
- Inspects the output files

**Step 4:** Check the generated files make sense:
- `.geo.json` has valid geometry with correct texture_width/height
- Atlas PNG looks correct (textures side by side)
- Attachable JSON has valid structure
- Geyser mappings JSON has correct format_version and model keys

**Step 5:** Commit:
```
git add -A
git commit -m "chore: successful build of FMM bedrock conversion pipeline"
```

---

## Task 11: Testbed Deployment and Transform Tuning

**Step 1:** Deploy to a test server with Geyser + FMM + EliteMobs.

**Step 2:** Enable bedrock conversion in ResourcePackManager config:
```yaml
bedrockConversionEnabled: true
bedrockAutoDeployToGeyser: true
```

**Step 3:** Restart server. Check console for conversion logs.

**Step 4:** Connect with a Bedrock client. Observe FMM model rendering.

**Step 5:** If models are invisible, wrongly positioned, or wrongly scaled, adjust the constants in `FmmAttachableGenerator`:
- `HEAD_POS_X/Y/Z` — position on the head bone
- `HEAD_ROT_X/Y/Z` — rotation
- `HEAD_SCALE_X/Y/Z` — scale

**Step 6:** Iterate: change values, rebuild, restart, observe. This is the empirical tuning step.

**Step 7:** Once values look right, commit:
```
git add -u
git commit -m "tune: adjust head-slot transforms for correct FMM model rendering on Bedrock"
```
