package com.magmaguy.resourcepackmanager.config;

import com.magmaguy.magmacore.config.ConfigurationEngine;
import com.magmaguy.magmacore.config.ConfigurationFile;
import lombok.Getter;

import java.util.List;

/**
 * User-tunable base offsets applied during Java→Bedrock display-transform
 * conversion. Each setting is a single number added on top of the
 * algorithmic conversion that runs inside FmmAnimationGenerator.
 * <p>
 * First-person and third-person are completely separate render passes in
 * Bedrock (they hit different parent bones with different rest poses), so
 * each has its own independent set of six knobs. Tuning one does not affect
 * the other.
 * <p>
 * If users report the held-in-hand model sitting wrong in either view, the
 * intended workflow is: have them try small adjustments to the relevant
 * axis until it looks right. The Bedrock client live-reloads attachable
 * JSON without a relaunch, so iteration is fast.
 */
public class BedrockDisplayOffsetsConfig extends ConfigurationFile {

    // ===== First-person =====
    @Getter
    private static double firstPersonBaseRotationX;
    @Getter
    private static double firstPersonBaseRotationY;
    @Getter
    private static double firstPersonBaseRotationZ;
    @Getter
    private static double firstPersonBasePositionX;
    @Getter
    private static double firstPersonBasePositionY;
    @Getter
    private static double firstPersonBasePositionZ;

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
        // ─────────────────────────────────────────────────────────────
        // First-person (right hand)
        // ─────────────────────────────────────────────────────────────
        // What "first-person" means here: the model viewed by the player
        // holding the item, when their own camera is rendering the held
        // item (the floating sword/tool visible in the bottom-right corner
        // of their own screen).
        //
        // Bedrock renders this through a dedicated attachable bone with its
        // own rest pose; the values below are added to the algorithmic
        // conversion result before it's written to the Bedrock animation
        // JSON. Defaults match the inherited Rainbow formula behaviour.
        // ─────────────────────────────────────────────────────────────

        firstPersonBaseRotationX = ConfigurationEngine.setDouble(
                List.of("First-person base rotation around the X axis, in degrees.",
                        "X axis in Bedrock first-person space is roughly 'pitch' (tipping the model nose-up/nose-down toward the camera).",
                        "Default -90 cancels the parent bone's built-in rotation so the Java pose is preserved.",
                        "If the item appears tilted away from or into the camera, nudge this value."),
                fileConfiguration, "firstPersonBaseRotationX", -90.0);

        firstPersonBaseRotationY = ConfigurationEngine.setDouble(
                List.of("First-person base rotation around the Y axis, in degrees.",
                        "Y axis is 'yaw' (spinning the model left/right around its vertical line).",
                        "Default 0; raise/lower if the item looks twisted relative to the player's forward direction."),
                fileConfiguration, "firstPersonBaseRotationY", 0.0);

        firstPersonBaseRotationZ = ConfigurationEngine.setDouble(
                List.of("First-person base rotation around the Z axis, in degrees.",
                        "Z axis is 'roll' (rotating the model around the axis pointing forward from the camera).",
                        "Default 0; adjust if the item is held with the wrong edge up."),
                fileConfiguration, "firstPersonBaseRotationZ", 0.0);

        firstPersonBasePositionX = ConfigurationEngine.setDouble(
                List.of("First-person base position offset on the X axis, in pixels (1 = 1/16 of a block).",
                        "X axis in first-person Bedrock space is roughly vertical from the player's perspective (up/down on screen).",
                        "Positive values push the model up the screen; negative pushes it down.",
                        "Default 0."),
                fileConfiguration, "firstPersonBasePositionX", 0.0);

        firstPersonBasePositionY = ConfigurationEngine.setDouble(
                List.of("First-person base position offset on the Y axis, in pixels (1 = 1/16 of a block).",
                        "Y axis in first-person Bedrock space is depth (toward/away from the camera).",
                        "Positive values push the model away from the camera; negative pulls it closer.",
                        "Default 12.5 — this is the inherited offset that compensates for the parent bone's rest position. Lowering it makes the item sit closer to the screen; raising it pushes it further into the scene."),
                fileConfiguration, "firstPersonBasePositionY", 12.5);

        firstPersonBasePositionZ = ConfigurationEngine.setDouble(
                List.of("First-person base position offset on the Z axis, in pixels (1 = 1/16 of a block).",
                        "Z axis in first-person Bedrock space is roughly horizontal (left/right on screen).",
                        "Positive values push the model right; negative pushes it left.",
                        "Default 0."),
                fileConfiguration, "firstPersonBasePositionZ", 0.0);

        // ─────────────────────────────────────────────────────────────
        // Third-person (right hand)
        // ─────────────────────────────────────────────────────────────
        // What "third-person" means here: the model as seen by OTHER
        // players (or by the holder in F5 / cinematic camera). This is a
        // completely separate Bedrock bone from the first-person view and
        // has its own rest pose, so it gets its own independent knobs.
        // Tuning first-person values has no effect on third-person and
        // vice versa.
        // ─────────────────────────────────────────────────────────────

        thirdPersonBaseRotationX = ConfigurationEngine.setDouble(
                List.of("Third-person base rotation around the X axis, in degrees.",
                        "X axis in Bedrock third-person space is 'pitch' (tipping the held item forward/backward as observers see it).",
                        "Default +90; the opposite sign of first-person because the third-person parent bone rests rotated the other way.",
                        "Adjust if the item points the wrong direction when other players look at it."),
                fileConfiguration, "thirdPersonBaseRotationX", 90.0);

        thirdPersonBaseRotationY = ConfigurationEngine.setDouble(
                List.of("Third-person base rotation around the Y axis, in degrees.",
                        "Y axis is 'yaw' (spinning the item left/right around its vertical line as observers see it).",
                        "Default 0."),
                fileConfiguration, "thirdPersonBaseRotationY", 0.0);

        thirdPersonBaseRotationZ = ConfigurationEngine.setDouble(
                List.of("Third-person base rotation around the Z axis, in degrees.",
                        "Z axis is 'roll' (rotating the item around its long axis as observers see it).",
                        "Default 0."),
                fileConfiguration, "thirdPersonBaseRotationZ", 0.0);

        thirdPersonBasePositionX = ConfigurationEngine.setDouble(
                List.of("Third-person base position offset on the X axis, in pixels (1 = 1/16 of a block).",
                        "X axis is roughly horizontal in third-person Bedrock space (across the holder's body).",
                        "Positive values push the model outward from the body; negative pulls it inward.",
                        "Default 0."),
                fileConfiguration, "thirdPersonBasePositionX", 0.0);

        thirdPersonBasePositionY = ConfigurationEngine.setDouble(
                List.of("Third-person base position offset on the Y axis, in pixels (1 = 1/16 of a block).",
                        "Y axis is vertical in third-person Bedrock space.",
                        "Positive values raise the model relative to the hand; negative lowers it.",
                        "Default 12.5 — inherited offset compensating for the parent bone's rest position. If the item floats above or sinks below where it should grip, this is the knob."),
                fileConfiguration, "thirdPersonBasePositionY", 12.5);

        thirdPersonBasePositionZ = ConfigurationEngine.setDouble(
                List.of("Third-person base position offset on the Z axis, in pixels (1 = 1/16 of a block).",
                        "Z axis is depth in third-person Bedrock space (forward/back relative to the holder).",
                        "Positive values push the model forward of the hand; negative pulls it backward.",
                        "Default 0."),
                fileConfiguration, "thirdPersonBasePositionZ", 0.0);
    }
}
