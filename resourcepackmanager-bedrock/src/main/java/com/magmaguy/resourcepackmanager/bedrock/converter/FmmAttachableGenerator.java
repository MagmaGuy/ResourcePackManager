package com.magmaguy.resourcepackmanager.bedrock.converter;

import com.google.gson.*;
import com.magmaguy.resourcepackmanager.bedrock.BedrockLog;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Generates Bedrock attachable definitions for FMM bone models, mirroring
 * Rainbow's {@code BedrockAttachableContext} geometry path 1:1, with one
 * deliberate divergence for FMM's use case (see materials note below).
 *
 * Emits:
 *  - materials.default = "entity_emissive_alpha" (Bedrock Wiki vanilla material
 *    list: "Emissive, Alpha Channel, Transparency"). Rainbow uses
 *    "entity_alphatest"; we diverge because FMM frequently spawns furniture
 *    armor stands intersecting floor/wall blocks, and "entity_alphatest"
 *    samples block-light at the entity position, rendering the model pitch
 *    black inside solid blocks on Bedrock. "entity_emissive_alpha" preserves
 *    alpha cutout while adding emissive contribution so the model stays
 *    visible regardless of block light. Note: emissivity is alpha-proportional
 *    per Bedrock spec (the-bedrock-notebook.dev materials/topics/defines),
 *    so fully-opaque pixels still partially receive lighting — if in-game
 *    testing shows persistent dimming, the fallback is to nudge texture
 *    alpha 255 -> 254 in TextureStitcher.
 *  - animations.first_person / third_person / head referencing the three
 *    animations produced by {@link FmmAnimationGenerator}.
 *  - scripts.animate with three context-conditional entries (no pre_animation
 *    indirection; matches BedrockAttachableContext.java:68-75).
 */
public class FmmAttachableGenerator {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static final String ATTACHABLE_FORMAT_VERSION = "1.21.0";

    /**
     * Generates an attachable JSON file and its companion three-pose animation file for one bone.
     * FMM-style wrapper: builds the FMM-prefixed identifiers/paths, then splits the work into
     * {@link #prepareAnimations(String, String, JsonObject, File)} (called once per model) and
     * {@link #writeAttachable(String, String, String, String, FmmAnimationGenerator.AnimationIds, File)}
     * (called once per attachable file).
     *
     * @param javaModel parsed bone JSON; {@code display.head} (if present) is forwarded
     *                  to the animation generator so Rainbow's head-display transform formula
     *                  (translation × -0.655/+0.655/+0.655 + (0, 20, 0), scale × 0.655) is applied
     * @return the bedrock identifier for use in Geyser mappings, or null on failure
     */
    public static String generate(String modelName, String boneName,
                                  String geometryId, String bedrockTexturePath,
                                  JsonObject javaModel,
                                  File bedrockPackDir) {
        // FMM-style identifier/path construction. The wrapper preserves the legacy
        // "freeminecraftmodels:<model>_<bone>" identifier so existing setItemModel()
        // calls (on the FMM plugin side) remain valid.
        String bedrockIdentifier = "freeminecraftmodels:" + modelName + "_" + boneName;
        String animBaseId = "fmm." + modelName + "." + boneName;
        String animFileBase = modelName + "__" + boneName;
        String attachableOutputPath = modelName + "/" + boneName;

        FmmAnimationGenerator.AnimationIds animIds =
                prepareAnimations(animBaseId, animFileBase, javaModel, bedrockPackDir);
        if (animIds == null) {
            BedrockLog.warn("[BedrockConverter] Failed to write animations for " + bedrockIdentifier);
            return null;
        }
        return writeAttachable(bedrockIdentifier, attachableOutputPath, geometryId,
                bedrockTexturePath, animIds, bedrockPackDir);
    }

    /**
     * Phase G — Step 1 of 2 for the generic pipeline. Writes the three-pose animation
     * file for one model and returns the resulting animation identifiers. Should be
     * called ONCE per (model) so multiple per-base-item attachables can reference the
     * same animation set without re-emitting files.
     *
     * @param animBaseId      identifier base for the three animations, e.g.
     *                        {@code "elitemobs.gear_bronze_sword"}; the resulting animation IDs
     *                        are {@code "animation.<animBaseId>.hold_first_person"} etc.
     * @param animFileBase    filename stem for the animation file (no extension), e.g.
     *                        {@code "elitemobs__gear_bronze_sword"} writes to
     *                        {@code animations/<animFileBase>.animation.json}
     * @param javaModel       parsed Java model JSON; {@code display.head} (if present) is
     *                        forwarded to the animation generator for head-slot transform
     * @param bedrockPackDir  output pack root
     * @return the animation identifier triple on success, {@code null} on failure
     */
    public static FmmAnimationGenerator.AnimationIds prepareAnimations(String animBaseId,
                                                                       String animFileBase,
                                                                       JsonObject javaModel,
                                                                       File bedrockPackDir) {
        FmmAnimationGenerator.JavaDisplay headDisplay = parseHeadDisplay(javaModel);
        FmmAnimationGenerator.JavaDisplay firstPersonDisplay = parseFirstPersonDisplay(javaModel);
        FmmAnimationGenerator.JavaDisplay thirdPersonDisplay = parseThirdPersonDisplay(javaModel);
        return FmmAnimationGenerator.generate(animBaseId, animFileBase,
                headDisplay, firstPersonDisplay, thirdPersonDisplay, bedrockPackDir);
    }

    /**
     * Phase G — Step 2 of 2 for the generic pipeline. Writes ONE attachable JSON file
     * referencing the (already-emitted) animation set and geometry. Called N times per
     * model (once per candidate base item) with a unique {@code bedrockIdentifier} each.
     *
     * @param bedrockIdentifier     Bedrock-side attachable identifier, e.g.
     *                              {@code "elitemobs:gear_bronze_sword__minecraft_diamond_sword"}.
     *                              Per Geyser docs, this MUST be globally unique across all
     *                              custom item definitions.
     * @param attachableOutputPath  filesystem stem under {@code attachables/} (no extension),
     *                              e.g. {@code "elitemobs/gear_bronze_sword__minecraft_diamond_sword"}
     *                              writes to
     *                              {@code attachables/elitemobs/gear_bronze_sword__minecraft_diamond_sword.json}
     * @param geometryId            geometry identifier emitted by FmmGeometryConverter (shared per model)
     * @param bedrockTexturePath    Bedrock texture path emitted by TextureStitcher (shared per model)
     * @param animIds               animation identifier triple returned by
     *                              {@link #prepareAnimations} (shared per model)
     * @param bedrockPackDir        output pack root
     * @return the {@code bedrockIdentifier} on success, {@code null} on failure
     */
    public static String writeAttachable(String bedrockIdentifier,
                                         String attachableOutputPath,
                                         String geometryId,
                                         String bedrockTexturePath,
                                         FmmAnimationGenerator.AnimationIds animIds,
                                         File bedrockPackDir) {
        if (!writeAttachableInternal(bedrockIdentifier, geometryId, bedrockTexturePath,
                animIds, attachableOutputPath, bedrockPackDir)) {
            BedrockLog.warn("[BedrockConverter] Failed to write attachable for " + bedrockIdentifier);
            return null;
        }
        return bedrockIdentifier;
    }

    private static boolean writeAttachableInternal(String identifier, String geometryId,
                                                   String texturePath,
                                                   FmmAnimationGenerator.AnimationIds animIds,
                                                   String attachableOutputPath,
                                                   File bedrockPackDir) {
        JsonObject description = new JsonObject();
        description.addProperty("identifier", identifier);

        // Materials: "entity_emissive_alpha" preserves alpha cutout AND was the
        // Wave-1 state that resolved the alpha-transparency complaint. Note that
        // it does NOT fix the "inside-a-block renders pitch black" symptom on its
        // own: Bedrock's USE_EMISSIVE define is alpha-proportional, so opaque
        // (alpha=255) pixels still sample block-light at the entity position.
        // A custom material with USE_ONLY_EMISSIVE was attempted but Bedrock's
        // custom-material inheritance form ("name:parent") errors silently in
        // 1.16.100+, so that path is dead (MS Learn + Bedrock Wiki concur). The
        // real fix for the inside-a-block dark render is FMM-side (raise the
        // armor stand Y or equivalent) — material-side is exhausted.
        JsonObject materials = new JsonObject();
        materials.addProperty("default", "entity_emissive_alpha");
        materials.addProperty("enchanted", "entity_alphatest_glint");
        description.add("materials", materials);

        JsonObject textures = new JsonObject();
        textures.addProperty("default", texturePath);
        textures.addProperty("enchanted", "textures/misc/enchanted_item_glint");
        description.add("textures", textures);

        JsonObject geometry = new JsonObject();
        geometry.addProperty("default", geometryId);
        description.add("geometry", geometry);

        // Animations: three keys mapping to the three Rainbow-style identifiers.
        JsonObject animations = new JsonObject();
        animations.addProperty("first_person", animIds.firstPerson());
        animations.addProperty("third_person", animIds.thirdPerson());
        animations.addProperty("head", animIds.head());
        description.add("animations", animations);

        // scripts.animate: three conditional entries, matching Rainbow's
        // BedrockAttachableContext.java:68-75. Uses context.is_first_person and
        // context.item_slot (NOT q.* or v.*).
        JsonArray animate = new JsonArray();
        animate.add(conditionEntry("first_person",
                "context.is_first_person == 1.0 && (context.item_slot == 'main_hand' || context.item_slot == 'off_hand')"));
        animate.add(conditionEntry("third_person",
                "context.is_first_person == 0.0 && (context.item_slot == 'main_hand' || context.item_slot == 'off_hand')"));
        animate.add(conditionEntry("head",
                "context.is_first_person == 0.0 && context.item_slot == 'head'"));
        JsonObject scripts = new JsonObject();
        scripts.add("animate", animate);
        description.add("scripts", scripts);

        JsonArray renderControllers = new JsonArray();
        renderControllers.add("controller.render.item_default");
        description.add("render_controllers", renderControllers);

        JsonObject attachable = new JsonObject();
        attachable.add("description", description);

        JsonObject root = new JsonObject();
        root.addProperty("format_version", ATTACHABLE_FORMAT_VERSION);
        root.add("minecraft:attachable", attachable);

        File outputFile = new File(bedrockPackDir, "attachables/" + attachableOutputPath + ".json");
        return writeJson(outputFile, root);
    }

    private static JsonObject conditionEntry(String key, String expression) {
        JsonObject entry = new JsonObject();
        entry.addProperty(key, expression);
        return entry;
    }

    /**
     * Reads {@code display.head} from the bone JSON and wraps it as a {@link FmmAnimationGenerator.JavaDisplay}.
     * FMM writes {@code display.head = { translation: [...], scale: [...] }} on every bone
     * ({@code BoneBlueprint.setDisplay}, line 395-404), with {@code MODEL_SCALE = 4f}.
     * Translation defaults to (0,0,0); rotation defaults to (0,0,0); scale defaults to 1.
     * The scale field may be a 3-element array or a scalar.
     */
    private static FmmAnimationGenerator.JavaDisplay parseHeadDisplay(JsonObject javaModel) {
        return parseDisplaySlot(javaModel, "head");
    }

    /**
     * Reads {@code display.firstperson_righthand} from the model JSON. Bronze-sword-style
     * source models (handheld weapons) populate this slot with the first-person rig pose;
     * FMM bones leave it absent (their only display slot is {@code head}). Returns null
     * when the block is absent or all-identity so the caller can fall through to identity
     * base values.
     */
    private static FmmAnimationGenerator.JavaDisplay parseFirstPersonDisplay(JsonObject javaModel) {
        return parseDisplaySlot(javaModel, "firstperson_righthand");
    }

    /**
     * Reads {@code display.thirdperson_righthand} from the model JSON. See
     * {@link #parseFirstPersonDisplay} — same shape, same null-on-absent contract.
     */
    private static FmmAnimationGenerator.JavaDisplay parseThirdPersonDisplay(JsonObject javaModel) {
        return parseDisplaySlot(javaModel, "thirdperson_righthand");
    }

    /**
     * Shared parser for any {@code display.<slot>} block. Returns null when:
     *   - the model has no {@code display} object,
     *   - the named slot is absent or not a JSON object, or
     *   - every field is identity (translation 0/0/0, rotation 0/0/0, scale 1).
     * Scale accepts either a 3-element array (FMM convention) or a scalar (vanilla
     * pre-1.20.5 minecraft model convention).
     */
    private static FmmAnimationGenerator.JavaDisplay parseDisplaySlot(JsonObject javaModel, String slotName) {
        if (javaModel == null || !javaModel.has("display") || !javaModel.get("display").isJsonObject()) {
            return null;
        }
        JsonObject display = javaModel.getAsJsonObject("display");
        if (!display.has(slotName) || !display.get(slotName).isJsonObject()) return null;
        JsonObject slot = display.getAsJsonObject(slotName);

        double[] translation = {0.0, 0.0, 0.0};
        if (slot.has("translation") && slot.get("translation").isJsonArray()) {
            JsonArray a = slot.getAsJsonArray("translation");
            for (int i = 0; i < 3 && i < a.size(); i++) translation[i] = a.get(i).getAsDouble();
        }
        double[] rotation = {0.0, 0.0, 0.0};
        if (slot.has("rotation") && slot.get("rotation").isJsonArray()) {
            JsonArray a = slot.getAsJsonArray("rotation");
            for (int i = 0; i < 3 && i < a.size(); i++) rotation[i] = a.get(i).getAsDouble();
        }
        double scale = 1.0;
        if (slot.has("scale")) {
            JsonElement s = slot.get("scale");
            if (s.isJsonArray()) {
                // Rainbow uses a uniform scale; both FMM and vanilla weapon models emit
                // a 3-element array. Take the X component (all three are the same).
                JsonArray arr = s.getAsJsonArray();
                if (arr.size() > 0) scale = arr.get(0).getAsDouble();
            } else if (s.isJsonPrimitive()) {
                scale = s.getAsDouble();
            }
        }
        // Early-out for identity to avoid emitting redundant non-trivial transforms.
        if (translation[0] == 0 && translation[1] == 0 && translation[2] == 0
                && rotation[0] == 0 && rotation[1] == 0 && rotation[2] == 0
                && scale == 1.0) {
            return null;
        }
        return new FmmAnimationGenerator.JavaDisplay(translation, rotation, scale);
    }

    private static boolean writeJson(File file, JsonObject json) {
        try {
            Files.createDirectories(file.getParentFile().toPath());
            try (FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(json, writer);
            }
            return true;
        } catch (IOException e) {
            BedrockLog.warn("[BedrockConverter] Failed to write " + file.getPath() + ": " + e.getMessage());
            return false;
        }
    }
}
