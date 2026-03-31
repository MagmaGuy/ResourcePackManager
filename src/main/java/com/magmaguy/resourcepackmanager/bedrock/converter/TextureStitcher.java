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
     */
    public static StitchResult stitch(String modelName, List<File> boneJsonFiles,
                                      File mergedPackRoot, File bedrockPackDir) {
        // 1. Collect unique texture references across all bones
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

                // Handle animated textures: if height > width, take first frame
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
