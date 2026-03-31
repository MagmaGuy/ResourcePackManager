package com.magmaguy.resourcepackmanager.bedrock.converter;

import com.google.gson.*;
import com.magmaguy.magmacore.util.Logger;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Generates Bedrock attachable definitions and head-slot animations for FMM bone models.
 * Transform values are centralised at the top for easy tuning.
 */
public class FmmAttachableGenerator {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    // ========================================================================
    // HEAD SLOT TRANSFORM VALUES — TUNE THESE AGAINST TESTBED
    // ========================================================================
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
     * @return the bedrock identifier for use in Geyser mappings, or null on failure
     */
    public static String generate(String modelName, String boneName,
                                  String geometryId, String bedrockTexturePath,
                                  File bedrockPackDir) {
        String bedrockIdentifier = "freeminecraftmodels:" + modelName + "_" + boneName;
        String animationId = "animation.fmm." + modelName + "." + boneName + ".head";

        if (!writeAnimation(modelName, boneName, animationId, bedrockPackDir)) {
            Logger.warn("[BedrockConverter] Failed to write animation for " + modelName + "/" + boneName);
            return null;
        }

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

        JsonObject materials = new JsonObject();
        materials.addProperty("default", "entity");
        materials.addProperty("enchanted", "entity_alphatest_glint");
        description.add("materials", materials);

        JsonObject textures = new JsonObject();
        textures.addProperty("default", texturePath);
        textures.addProperty("enchanted", "textures/misc/enchanted_item_glint");
        description.add("textures", textures);

        JsonObject geometry = new JsonObject();
        geometry.addProperty("default", geometryId);
        description.add("geometry", geometry);

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

        JsonObject animations = new JsonObject();
        animations.addProperty("head", animationId);
        description.add("animations", animations);

        JsonArray renderControllers = new JsonArray();
        renderControllers.add("controller.render.item_default");
        description.add("render_controllers", renderControllers);

        JsonObject attachable = new JsonObject();
        attachable.add("description", description);

        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.10.0");
        root.add("minecraft:attachable", attachable);

        File outputFile = new File(bedrockPackDir, "attachables/" + modelName + "/" + boneName + ".json");
        return writeJson(outputFile, root);
    }

    private static boolean writeAnimation(String modelName, String boneName,
                                           String animationId, File bedrockPackDir) {
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
