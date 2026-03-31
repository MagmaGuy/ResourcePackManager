package com.magmaguy.resourcepackmanager.bedrock;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.bedrock.converter.*;
import com.magmaguy.resourcepackmanager.bedrock.model.BedrockManifest;
import com.magmaguy.resourcepackmanager.bedrock.model.FmmBoneModel;
import com.magmaguy.resourcepackmanager.bedrock.util.BedrockZip;
import com.magmaguy.resourcepackmanager.config.DefaultConfig;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Main orchestrator for converting FreeMinecraftModels bone models into a Bedrock
 * resource pack and Geyser custom mappings file.
 * <p>
 * Pipeline: scan FMM models → stitch textures → convert geometry → generate
 * attachables → generate Geyser v2 mappings → zip → deploy to Geyser.
 */
public class BedrockConversion {

    private static final String BEDROCK_PACK_NAME = "ResourcePackManager_Bedrock";
    private static final String GEYSER_MAPPINGS_NAME = "rspm_geyser_mappings.json";

    /**
     * Called early during RSPM startup (before Geyser loads packs) to ensure
     * any previously generated Bedrock pack is deployed to Geyser's folders.
     */
    public static void deployPreviousIfNeeded() {
        if (!DefaultConfig.isBedrockConversionEnabled()) return;
        if (!DefaultConfig.isBedrockAutoDeployToGeyser()) return;

        File outputDir = new File(ResourcePackManager.plugin.getDataFolder(), "output");
        File previousPack = new File(outputDir, BEDROCK_PACK_NAME + ".zip");
        File previousMappings = new File(outputDir, GEYSER_MAPPINGS_NAME);

        if (!previousPack.exists() || !previousMappings.exists()) return;

        GeyserDeployer.deploy(previousPack, previousMappings);
        Logger.info("Pre-deployed previous Bedrock pack to Geyser for immediate loading.");
    }

    /**
     * Main entry point. Called from Mix.java after the Java pack is zipped
     * but before the unzipped folder is cleaned up.
     *
     * @param mergedJavaPack the unzipped merged Java resource pack directory
     * @param outputDir      the output directory containing the merged pack
     */
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
            Map<String, String> iconTextureMap = new LinkedHashMap<>(); // iconKey -> bedrockTexturePath
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
                    iconTextureMap.put(iconKey, stitch.bedrockTexturePath());
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

            // 4. Generate item_texture.json (required by Geyser for icon resolution)
            generateItemTexture(iconTextureMap, bedrockDir);

            // 5. Generate manifest
            String contentHash = String.valueOf(System.currentTimeMillis());
            String pluginVersion = ResourcePackManager.plugin.getDescription().getVersion();
            BedrockManifest.write(bedrockDir, pluginVersion, contentHash);

            // 6. Generate Geyser mappings
            File mappingsFile = new File(outputDir, GEYSER_MAPPINGS_NAME);
            FmmGeyserMappingBuilder.generate(allMappings, mappingsFile);

            // 7. Zip
            File bedrockZip = BedrockZip.zip(bedrockDir, outputDir, BEDROCK_PACK_NAME);
            if (bedrockZip == null) {
                Logger.warn("Failed to zip Bedrock resource pack!");
                return;
            }
            recursivelyDelete(bedrockDir);

            // 8. Deploy to Geyser
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

    /**
     * Generates item_texture.json in the Bedrock pack.
     * Maps each bone's icon key to its model's atlas texture path.
     * Required by Geyser to resolve item icons for custom items.
     */
    private static void generateItemTexture(Map<String, String> iconTextureMap, File bedrockDir) {
        JsonObject textureData = new JsonObject();
        for (Map.Entry<String, String> entry : iconTextureMap.entrySet()) {
            JsonObject texEntry = new JsonObject();
            texEntry.addProperty("textures", entry.getValue());
            textureData.add(entry.getKey(), texEntry);
        }

        JsonObject root = new JsonObject();
        root.addProperty("resource_pack_name", "ResourcePackManager_Bedrock");
        root.addProperty("texture_name", "atlas.items");
        root.add("texture_data", textureData);

        File outputFile = new File(bedrockDir, "textures/item_texture.json");
        try {
            Files.createDirectories(outputFile.getParentFile().toPath());
            try (FileWriter writer = new FileWriter(outputFile, StandardCharsets.UTF_8)) {
                new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
                        .toJson(root, writer);
            }
            Logger.info("[BedrockConverter] Generated item_texture.json with " + iconTextureMap.size() + " entries.");
        } catch (IOException e) {
            Logger.warn("[BedrockConverter] Failed to write item_texture.json: " + e.getMessage());
        }
    }

    /**
     * Parses a JSON file into a JsonObject.
     */
    private static JsonObject parseJsonFile(File file) {
        try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            Logger.warn("[BedrockConverter] Failed to parse " + file.getPath() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Copies pack.png from the Java pack root to pack_icon.png in the Bedrock pack root.
     */
    private static void copyPackIcon(File mergedPackRoot, File bedrockDir) {
        File packPng = new File(mergedPackRoot, "pack.png");
        if (!packPng.exists()) return;
        try {
            Files.copy(packPng.toPath(), new File(bedrockDir, "pack_icon.png").toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Logger.warn("Failed to copy pack icon: " + e.getMessage());
        }
    }

    /**
     * Recursively deletes a directory and all of its contents.
     */
    private static void recursivelyDelete(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    recursivelyDelete(child);
                }
            }
        }
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException e) {
            Logger.warn("Failed to delete " + file.getPath() + ": " + e.getMessage());
        }
    }
}
