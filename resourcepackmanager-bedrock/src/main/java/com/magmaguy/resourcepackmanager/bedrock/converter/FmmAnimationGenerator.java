package com.magmaguy.resourcepackmanager.bedrock.converter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.magmaguy.resourcepackmanager.bedrock.BedrockLog;

import java.io.File;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Generates per-item Bedrock animation JSON containing the three Rainbow-style
 * animations (hold_first_person, hold_third_person, head) for one model bone.
 *
 * First and third person are derived from the vanilla Bedrock player rig so the held model
 * matches Java (see {@link HeldItemTransform}).
 *
 * Layout: one file per identifier at animations/&lt;modelName&gt;__&lt;boneName&gt;.animation.json
 * containing three animation entries:
 *   - animation.&lt;animBaseId&gt;.hold_first_person
 *   - animation.&lt;animBaseId&gt;.hold_third_person
 *   - animation.&lt;animBaseId&gt;.head
 *
 * Each entry: loop=true, single bone "bone" with position/rotation/scale arrays.
 *
 * Default head transform: position (0, 20, 0), scale 0.655.
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

    private static final double HEAD_BASE_POS_Y = 20.0;
    private static final double HEAD_SCALE_FACTOR = 0.655;

    private static final String BONE_NAME = "bone";

    private FmmAnimationGenerator() {}

    /**
     * One Java {@code display.<slot>} transform. When a slot is null, identity is used;
     * for the head that yields Rainbow's base values: position (0, 20, 0), rotation (0, 0, 0),
     * scale (0.655, 0.655, 0.655).
     */
    public static final class JavaDisplay {
        public final double[] translation; // 3-element, pixels
        public final double[] rotation;    // 3-element, degrees
        public final double[] scale;       // 3-element; the head uses the X component

        public JavaDisplay(double[] translation, double[] rotation, double[] scale) {
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
     * <p>Each of the three display arguments is optional; when null, the held poses use
     * Java's identity transform and the head uses Rainbow's base values. The held poses are
     * solved by {@link HeldItemTransform}.
     *
     * @param animBaseId             identifier base (without "animation." prefix)
     * @param fileBaseName           filename base; output is animations/&lt;fileBaseName&gt;.animation.json
     * @param javaHeadDisplay        optional Java display.head transform; null = identity
     * @param javaFirstPersonDisplay optional Java display.firstperson_righthand transform; null = identity
     * @param javaThirdPersonDisplay optional Java display.thirdperson_righthand transform; null = identity
     * @param bonePivot              pivot of the geometry bone the animations drive, in Bedrock file
     *                               coordinates ({@code FmmGeometryConverter.bonePivot})
     * @param bedrockPackDir         pack root
     * @return the three fully-qualified animation identifiers, or null on failure
     */
    public static AnimationIds generate(String animBaseId,
                                        String fileBaseName,
                                        JavaDisplay javaHeadDisplay,
                                        JavaDisplay javaFirstPersonDisplay,
                                        JavaDisplay javaThirdPersonDisplay,
                                        double[] bonePivot,
                                        File bedrockPackDir) {
        String fpId = "animation." + animBaseId + ".hold_first_person";
        String tpId = "animation." + animBaseId + ".hold_third_person";
        String hdId = "animation." + animBaseId + ".head";

        HeldItemTransform.BoneTransform firstPerson =
                HeldItemTransform.firstPerson(javaFirstPersonDisplay, bonePivot);
        HeldItemTransform.BoneTransform thirdPerson =
                HeldItemTransform.thirdPerson(javaThirdPersonDisplay, bonePivot);

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
            double s = javaHeadDisplay.scale[0] * HEAD_SCALE_FACTOR;
            headScale = new double[]{s, s, s};
        }

        JsonObject animations = new JsonObject();
        animations.add(fpId, buildAnim(firstPerson.position(), firstPerson.rotation(), firstPerson.scale()));
        animations.add(tpId, buildAnim(thirdPerson.position(), thirdPerson.rotation(), thirdPerson.scale()));
        animations.add(hdId, buildAnim(headPos, headRot, headScale));

        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.8.0");
        root.add("animations", animations);

        File outFile = new File(bedrockPackDir, "animations/" + fileBaseName + ".animation.json");
        try {
            Files.createDirectories(outFile.getParentFile().toPath());
            try (Writer w = new BufferedWriter(new FileWriter(outFile, StandardCharsets.UTF_8), 1 << 16)) {
                GSON.toJson(root, w);
            }
        } catch (IOException e) {
            BedrockLog.warn("[BedrockConverter] Failed to write animation " + outFile.getPath() + ": " + e.getMessage());
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
