package com.magmaguy.resourcepackmanager.bedrock;

/**
 * Static snapshot of the user-tunable display-transform offsets applied during
 * Java→Bedrock animation conversion. Set once at the start of
 * {@link BedrockConversion#generate} from the {@link BedrockConverterContext} and
 * read by {@code FmmAnimationGenerator} when it computes the third-person base
 * rotation+position offsets. First person has no offsets: it is derived from the
 * vanilla Bedrock first-person rig ({@code FirstPersonTransform}).
 *
 * <p>Same pattern as {@link BedrockLog}: rather than thread a snapshot parameter
 * through every animation/attachable helper, install it in a static slot for the
 * duration of one (non-concurrent) conversion run. Defaults to the community-tuned
 * FMM values so unit tests that exercise the animation helper directly
 * don't need to set up a context.</p>
 *
 * <p>The backend's Bukkit context reads from
 * {@code BedrockDisplayOffsetsConfig} (YAML-backed); the proxy context returns
 * {@link Snapshot#defaults()} because proxy admins are not expected to tweak
 * display offsets (and there's no obvious place for that YAML on the
 * proxy plugin).</p>
 */
public final class BedrockDisplayOffsets {

    /**
     * Six third-person doubles. See {@code BedrockDisplayOffsetsConfig} for
     * human-readable per-field docs.
     */
    public record Snapshot(
            double thirdPersonBaseRotationX,
            double thirdPersonBaseRotationY,
            double thirdPersonBaseRotationZ,
            double thirdPersonBasePositionX,
            double thirdPersonBasePositionY,
            double thirdPersonBasePositionZ) {

        /**
         * Community-validated defaults for FMM held-item conversion. Used by
         * any context that doesn't want to expose a YAML knob.
         */
        public static Snapshot defaults() {
            return new Snapshot(
                    90.0, 0.0, 0.0,
                    0.0, 6.0, -10.0);
        }
    }

    private static volatile Snapshot current = Snapshot.defaults();

    private BedrockDisplayOffsets() {}

    /**
     * Install a new snapshot for the duration of one conversion run. Passing
     * {@code null} resets to {@link Snapshot#defaults()}.
     */
    public static void set(Snapshot snapshot) {
        current = (snapshot != null) ? snapshot : Snapshot.defaults();
    }

    // Getters use the `get<Field>()` prefix to match the legacy
    // BedrockDisplayOffsetsConfig public API the call sites already depend on.
    public static double getThirdPersonBaseRotationX() { return current.thirdPersonBaseRotationX(); }
    public static double getThirdPersonBaseRotationY() { return current.thirdPersonBaseRotationY(); }
    public static double getThirdPersonBaseRotationZ() { return current.thirdPersonBaseRotationZ(); }
    public static double getThirdPersonBasePositionX() { return current.thirdPersonBasePositionX(); }
    public static double getThirdPersonBasePositionY() { return current.thirdPersonBasePositionY(); }
    public static double getThirdPersonBasePositionZ() { return current.thirdPersonBasePositionZ(); }
}
