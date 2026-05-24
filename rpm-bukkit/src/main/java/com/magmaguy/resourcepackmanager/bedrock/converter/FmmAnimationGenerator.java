package com.magmaguy.resourcepackmanager.bedrock.converter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.config.BedrockDisplayOffsetsConfig;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Generates per-item Bedrock animation JSON containing the three Rainbow-style
 * animations (hold_first_person, hold_third_person, head) for one model bone.
 *
 * Mirrors org.geysermc.rainbow.mapping.animation.AnimationMapper.
 *
 * Layout: one file per identifier at animations/&lt;modelName&gt;__&lt;boneName&gt;.animation.json
 * containing three animation entries:
 *   - animation.&lt;animBaseId&gt;.hold_first_person
 *   - animation.&lt;animBaseId&gt;.hold_third_person
 *   - animation.&lt;animBaseId&gt;.head
 *
 * Each entry: loop=true, single bone "bone" with position/rotation/scale arrays.
 *
 * Rainbow magic constants (AnimationMapper.java:25-50):
 *   First person base: rotation (-90, 0, 0), position (0, 12.5, 0)
 *   Third person base: rotation (+90, 0, 0), position (0, 12.5, 0)
 *   Head base:         position (0, 20, 0), scale 0.655
 * If a Java display.head transform is provided, the formula in Rainbow's
 * AnimationMapper.java:48 is:
 *   headPosition = (Java.translation / 0.0625) * (-0.655, 0.655, 0.655) + (0, 20, 0)
 * but the {@code /0.0625} step is Rainbow undoing Java's parser, which multiplies
 * pixel-unit JSON values by 0.0625 to get block units (see AnimationMapper.java:15
 * comment). RSPM reads the bone JSON directly, so translations are STILL in pixel
 * units — the divide step must NOT be applied here. The corrected formula is:
 *   headPosition = Java.translation * (-0.655, 0.655, 0.655) + (0, 20, 0)
 *   headRotation = (-Java.rotX, -Java.rotY, +Java.rotZ)
 *   headScale    = Java.scale * 0.655
 */
public final class FmmAnimationGenerator {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    // First/third-person base values are user-tunable via BedrockDisplayOffsetsConfig.
    // Head values stay as compile-time constants — they belong to a separate render
    // path (head-slot display, not held-in-hand) and aren't part of the offset-tuning
    // workflow the user-facing config exposes.
    private static final double HEAD_BASE_POS_Y = 20.0;
    private static final double HEAD_SCALE_FACTOR = 0.655;

    private static final String BONE_NAME = "bone";

    private FmmAnimationGenerator() {}

    /**
     * Optional Java display.head transform input. When null, identity is used,
     * yielding Rainbow's base values: position (0, 20, 0), rotation (0, 0, 0),
     * scale (0.655, 0.655, 0.655).
     */
    public static final class JavaDisplay {
        public final double[] translation; // 3-element
        public final double[] rotation;    // 3-element, degrees
        public final double scale;         // uniform scale

        public JavaDisplay(double[] translation, double[] rotation, double scale) {
            this.translation = translation;
            this.rotation = rotation;
            this.scale = scale;
        }
    }

    /** Result identifiers (fully qualified, including the "animation." prefix). */
    public record AnimationIds(String firstPerson, String thirdPerson, String head) {}

    /**
     * Writes one animation file containing all three Rainbow-style animations.
     *
     * <p>Each of the three display arguments is optional; when null, the corresponding
     * pose falls back to Rainbow's identity base values. The first/third-person formulas
     * (when displays are provided) mirror Rainbow's AnimationMapper.java:18-41:
     * <pre>
     *   FIRST-PERSON
     *   fp_rot = (-90 + jr.y, -jr.z, jr.x)
     *   fp_pos = (-jt.y, 12.5 + jt.z, jt.x)
     *
     *   THIRD-PERSON
     *   tp_rot = (+90, -jr.z, -jr.y)
     *   tp_pos = (-jt.x, 12.5 + jt.z, -jt.y)
     * </pre>
     *
     * <p>Critical: the {@code /0.0625} step Rainbow does is SKIPPED. RSPM reads the bone
     * JSON directly so translations are already in pixel units (same reason as the
     * existing head-display fix, see lines 117-122 below).
     *
     * @param animBaseId             identifier base (without "animation." prefix)
     * @param fileBaseName           filename base; output is animations/&lt;fileBaseName&gt;.animation.json
     * @param javaHeadDisplay        optional Java display.head transform; null = identity
     * @param javaFirstPersonDisplay optional Java display.firstperson_righthand transform; null = identity
     * @param javaThirdPersonDisplay optional Java display.thirdperson_righthand transform; null = identity
     * @param bedrockPackDir         pack root
     * @return the three fully-qualified animation identifiers, or null on failure
     */
    public static AnimationIds generate(String animBaseId,
                                        String fileBaseName,
                                        JavaDisplay javaHeadDisplay,
                                        JavaDisplay javaFirstPersonDisplay,
                                        JavaDisplay javaThirdPersonDisplay,
                                        File bedrockPackDir) {
        String fpId = "animation." + animBaseId + ".hold_first_person";
        String tpId = "animation." + animBaseId + ".hold_third_person";
        String hdId = "animation." + animBaseId + ".head";

        // First-person base offsets — user-tunable via bedrock_display_offsets.yml.
        // Defaults reproduce the inherited Rainbow formula: rotation (-90, 0, 0),
        // position (0, 12.5, 0). The base offsets are added on every axis so a
        // user reporting "the model floats too high in first person" can adjust
        // firstPersonBasePositionX directly without touching code.
        double fpBaseRotX = BedrockDisplayOffsetsConfig.getFirstPersonBaseRotationX();
        double fpBaseRotY = BedrockDisplayOffsetsConfig.getFirstPersonBaseRotationY();
        double fpBaseRotZ = BedrockDisplayOffsetsConfig.getFirstPersonBaseRotationZ();
        double fpBasePosX = BedrockDisplayOffsetsConfig.getFirstPersonBasePositionX();
        double fpBasePosY = BedrockDisplayOffsetsConfig.getFirstPersonBasePositionY();
        double fpBasePosZ = BedrockDisplayOffsetsConfig.getFirstPersonBasePositionZ();

        // First-person: identity defaults, then layer Java display.firstperson_righthand if provided.
        double[] fpPos;
        double[] fpRot;
        double[] fpScale;
        if (javaFirstPersonDisplay == null) {
            fpPos = new double[]{fpBasePosX, fpBasePosY, fpBasePosZ};
            fpRot = new double[]{fpBaseRotX, fpBaseRotY, fpBaseRotZ};
            fpScale = new double[]{1.0, 1.0, 1.0};
        } else {
            // fp_rot = (base_x + jr.y, base_y + -jr.z, base_z + jr.x)
            fpRot = new double[]{
                    fpBaseRotX + javaFirstPersonDisplay.rotation[1],
                    fpBaseRotY + -javaFirstPersonDisplay.rotation[2],
                    fpBaseRotZ + javaFirstPersonDisplay.rotation[0]
            };
            // fp_pos = (base_x + -jt.y, base_y + jt.z, base_z + jt.x)
            // Skip Rainbow's /0.0625 step — RSPM reads pixel-unit JSON directly.
            double jtx = javaFirstPersonDisplay.translation[0];
            double jty = javaFirstPersonDisplay.translation[1];
            double jtz = javaFirstPersonDisplay.translation[2];
            fpPos = new double[]{
                    fpBasePosX + -jty,
                    fpBasePosY + jtz,
                    fpBasePosZ + jtx
            };
            double s = javaFirstPersonDisplay.scale;
            fpScale = new double[]{s, s, s};
        }

        // Third-person base offsets — user-tunable via bedrock_display_offsets.yml.
        // First- and third-person are independent Bedrock render paths with their
        // own rest poses, so they get fully independent knob sets. Adjusting one
        // does not affect the other.
        double tpBaseRotX = BedrockDisplayOffsetsConfig.getThirdPersonBaseRotationX();
        double tpBaseRotY = BedrockDisplayOffsetsConfig.getThirdPersonBaseRotationY();
        double tpBaseRotZ = BedrockDisplayOffsetsConfig.getThirdPersonBaseRotationZ();
        double tpBasePosX = BedrockDisplayOffsetsConfig.getThirdPersonBasePositionX();
        double tpBasePosY = BedrockDisplayOffsetsConfig.getThirdPersonBasePositionY();
        double tpBasePosZ = BedrockDisplayOffsetsConfig.getThirdPersonBasePositionZ();

        // Third-person: identity defaults, then layer Java display.thirdperson_righthand if provided.
        double[] tpPos;
        double[] tpRot;
        double[] tpScale;
        if (javaThirdPersonDisplay == null) {
            tpPos = new double[]{tpBasePosX, tpBasePosY, tpBasePosZ};
            tpRot = new double[]{tpBaseRotX, tpBaseRotY, tpBaseRotZ};
            tpScale = new double[]{1.0, 1.0, 1.0};
        } else {
            // tp_rot = (base_x + jr.x, base_y + -jr.z, base_z + -jr.y)
            // EXPERIMENTAL: include the Java X rotation that Rainbow drops with
            // a `// TODO fix X rotation` comment. Mirrors the first-person
            // formula where Java's "primary" rotation axis (jr.y for FP, jr.x
            // for TP, per the axis-remap below) is added onto the Bedrock X
            // base. If this overshoots, try -jr.x; if it has no visible effect
            // for non-zero Java X, the parent bone's rest frame is rotating it
            // out and the fix has to come from somewhere else.
            tpRot = new double[]{
                    tpBaseRotX + javaThirdPersonDisplay.rotation[0],
                    tpBaseRotY + -javaThirdPersonDisplay.rotation[2],
                    tpBaseRotZ + -javaThirdPersonDisplay.rotation[1]
            };
            // tp_pos = (base_x + -jt.x, base_y + jt.z, base_z + -jt.y)
            // Skip Rainbow's /0.0625 step — RSPM reads pixel-unit JSON directly.
            double jtx = javaThirdPersonDisplay.translation[0];
            double jty = javaThirdPersonDisplay.translation[1];
            double jtz = javaThirdPersonDisplay.translation[2];
            tpPos = new double[]{
                    tpBasePosX + -jtx,
                    tpBasePosY + jtz,
                    tpBasePosZ + -jty
            };
            double s = javaThirdPersonDisplay.scale;
            tpScale = new double[]{s, s, s};
        }

        // Head: identity defaults, then layer Java display.head if provided.
        double[] headPos;
        double[] headRot;
        double[] headScale;
        if (javaHeadDisplay == null) {
            headPos = new double[]{0.0, HEAD_BASE_POS_Y, 0.0};
            headRot = new double[]{0.0, 0.0, 0.0};
            headScale = new double[]{HEAD_SCALE_FACTOR, HEAD_SCALE_FACTOR, HEAD_SCALE_FACTOR};
        } else {
            // headPosition = Java.translation * (-0.655, 0.655, 0.655) + (0, 20, 0)
            // NOTE: Rainbow's source divides translation by 0.0625 first to undo Java's
            // pixel→block conversion that happens in ItemTransform.Deserializer. RSPM
            // reads the bone JSON directly so values are already in pixel units; the
            // divide step would over-amplify the translation by 16× and put the bone
            // ~2.9 blocks below where it should be. Skip the divide.
            double tx = javaHeadDisplay.translation[0];
            double ty = javaHeadDisplay.translation[1];
            double tz = javaHeadDisplay.translation[2];
            headPos = new double[]{
                    tx * -HEAD_SCALE_FACTOR,
                    ty * HEAD_SCALE_FACTOR + HEAD_BASE_POS_Y,
                    tz * HEAD_SCALE_FACTOR
            };
            // headRotation = (-Java.rotX, -Java.rotY, +Java.rotZ)
            headRot = new double[]{
                    -javaHeadDisplay.rotation[0],
                    -javaHeadDisplay.rotation[1],
                    javaHeadDisplay.rotation[2]
            };
            // headScale = Java.scale * 0.655
            double s = javaHeadDisplay.scale * HEAD_SCALE_FACTOR;
            headScale = new double[]{s, s, s};
        }

        JsonObject animations = new JsonObject();
        animations.add(fpId, buildAnim(fpPos, fpRot, fpScale));
        animations.add(tpId, buildAnim(tpPos, tpRot, tpScale));
        animations.add(hdId, buildAnim(headPos, headRot, headScale));

        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.8.0");
        root.add("animations", animations);

        File outFile = new File(bedrockPackDir, "animations/" + fileBaseName + ".animation.json");
        try {
            Files.createDirectories(outFile.getParentFile().toPath());
            try (FileWriter w = new FileWriter(outFile, StandardCharsets.UTF_8)) {
                GSON.toJson(root, w);
            }
        } catch (IOException e) {
            Logger.warn("[BedrockConverter] Failed to write animation " + outFile.getPath() + ": " + e.getMessage());
            return null;
        }
        return new AnimationIds(fpId, tpId, hdId);
    }

    /**
     * Builds one animation entry. Each transform component is omitted when it is
     * identity (zero for position/rotation, one for scale) so Bedrock's bone
     * transform falls through to the entity bone's dynamic transform (e.g. armor
     * stand head pose forwarded by Geyser). Emitting an explicit identity value
     * on a {@code loop: true} animation otherwise clobbers entity transforms
     * every frame, which is what causes "models don't animate on Bedrock".
     */
    private static JsonObject buildAnim(double[] position, double[] rotation, double[] scale) {
        JsonObject boneEntry = new JsonObject();
        if (position != null && (position[0] != 0 || position[1] != 0 || position[2] != 0)) {
            boneEntry.add("position", toArr(position));
        }
        if (rotation != null && (rotation[0] != 0 || rotation[1] != 0 || rotation[2] != 0)) {
            boneEntry.add("rotation", toArr(rotation));
        }
        if (scale != null && (scale[0] != 1.0 || scale[1] != 1.0 || scale[2] != 1.0)) {
            boneEntry.add("scale", toArr(scale));
        }

        JsonObject bones = new JsonObject();
        bones.add(BONE_NAME, boneEntry);

        JsonObject entry = new JsonObject();
        entry.addProperty("loop", true);
        entry.add("bones", bones);
        return entry;
    }

    private static JsonArray toArr(double[] xyz) {
        JsonArray a = new JsonArray();
        for (double v : xyz) a.add(v);
        return a;
    }
}
