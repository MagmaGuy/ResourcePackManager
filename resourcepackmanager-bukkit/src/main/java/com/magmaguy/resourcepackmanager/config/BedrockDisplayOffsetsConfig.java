package com.magmaguy.resourcepackmanager.config;

import com.magmaguy.magmacore.config.ConfigurationEngine;
import com.magmaguy.magmacore.config.ConfigurationFile;
import com.magmaguy.resourcepackmanager.bedrock.BedrockDisplayOffsets;
import lombok.Getter;

import java.util.List;

/**
 * User-tunable third-person base offsets applied during Java→Bedrock
 * display-transform conversion. Each setting is a single number added on top
 * of the algorithmic conversion that runs inside FmmAnimationGenerator.
 * <p>
 * First person has no knobs: it is derived from the vanilla Bedrock
 * first-person rig so held models land where Java draws them
 * (FirstPersonTransform).
 * <p>
 * If users report the held-in-hand model sitting wrong in third person, the
 * intended workflow is: have them try small adjustments to the relevant
 * axis until it looks right. The Bedrock client live-reloads attachable
 * JSON without a relaunch, so iteration is fast.
 */
public class BedrockDisplayOffsetsConfig extends ConfigurationFile {

    // ===== Third-person =====
    @Getter
    private static double thirdPersonBaseRotationX;
    @Getter
    private static double thirdPersonBaseRotationY;
    @Getter
    private static double thirdPersonBaseRotationZ;
    @Getter
    private static double thirdPersonBasePositionX;
    @Getter
    private static double thirdPersonBasePositionY;
    @Getter
    private static double thirdPersonBasePositionZ;

    public BedrockDisplayOffsetsConfig() {
        super("bedrock_display_offsets.yml");
    }

    @Override
    public void initializeValues() {
        BedrockDisplayOffsets.Snapshot defaults = BedrockDisplayOffsets.Snapshot.defaults();

        // ─────────────────────────────────────────────────────────────
        // Third-person (right hand)
        // ─────────────────────────────────────────────────────────────
        // What "third-person" means here: the model as seen by OTHER
        // players (or by the holder in F5 / cinematic camera). Bedrock
        // renders it through the attachable's third-person animation; the
        // values below are added to the algorithmic conversion result
        // before it's written to the Bedrock animation JSON.
        // ─────────────────────────────────────────────────────────────

        thirdPersonBaseRotationX = ConfigurationEngine.setDouble(
                List.of("Third-person base rotation around the X axis, in degrees.",
                        "X axis in Bedrock third-person space is 'pitch' (tipping the held item forward/backward as observers see it).",
                        "Default +90 compensates for the third-person parent bone's rest pose.",
                        "Adjust if the item points the wrong direction when other players look at it."),
                fileConfiguration, "thirdPersonBaseRotationX", defaults.thirdPersonBaseRotationX());

        thirdPersonBaseRotationY = ConfigurationEngine.setDouble(
                List.of("Third-person base rotation around the Y axis, in degrees.",
                        "Y axis is 'yaw' (spinning the item left/right around its vertical line as observers see it).",
                        "Default 0."),
                fileConfiguration, "thirdPersonBaseRotationY", defaults.thirdPersonBaseRotationY());

        thirdPersonBaseRotationZ = ConfigurationEngine.setDouble(
                List.of("Third-person base rotation around the Z axis, in degrees.",
                        "Z axis is 'roll' (rotating the item around its long axis as observers see it).",
                        "Default 0."),
                fileConfiguration, "thirdPersonBaseRotationZ", defaults.thirdPersonBaseRotationZ());

        thirdPersonBasePositionX = ConfigurationEngine.setDouble(
                List.of("Third-person base position offset on the X axis, in pixels (1 = 1/16 of a block).",
                        "X axis is roughly horizontal in third-person Bedrock space (across the holder's body).",
                        "Positive values push the model outward from the body; negative pulls it inward.",
                        "Default 0."),
                fileConfiguration, "thirdPersonBasePositionX", defaults.thirdPersonBasePositionX());

        thirdPersonBasePositionY = ConfigurationEngine.setDouble(
                List.of("Third-person base position offset on the Y axis, in pixels (1 = 1/16 of a block).",
                        "Y axis is vertical in third-person Bedrock space.",
                        "Positive values raise the model relative to the hand; negative lowers it.",
                        "Default 6. If the item floats above or sinks below where it should grip, this is the knob."),
                fileConfiguration, "thirdPersonBasePositionY", defaults.thirdPersonBasePositionY());

        thirdPersonBasePositionZ = ConfigurationEngine.setDouble(
                List.of("Third-person base position offset on the Z axis, in pixels (1 = 1/16 of a block).",
                        "Z axis is depth in third-person Bedrock space (forward/back relative to the holder).",
                        "Positive values push the model forward of the hand; negative pulls it backward.",
                        "Default -10."),
                fileConfiguration, "thirdPersonBasePositionZ", defaults.thirdPersonBasePositionZ());
    }
}
