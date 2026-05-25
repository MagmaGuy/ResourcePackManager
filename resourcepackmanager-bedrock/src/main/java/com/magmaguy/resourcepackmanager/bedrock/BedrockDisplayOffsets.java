package com.magmaguy.resourcepackmanager.bedrock;

/**
 * Static snapshot of the user-tunable display-transform offsets applied during
 * Java→Bedrock animation conversion. Set once at the start of
 * {@link BedrockConversion#generate} from the {@link BedrockConverterContext} and
 * read by {@code FmmAnimationGenerator} when it computes the first-/third-person
 * base rotation+position offsets.
 *
 * <p>Same pattern as {@link BedrockLog}: rather than thread a snapshot parameter
 * through every animation/attachable helper, install it in a static slot for the
 * duration of one (non-concurrent) conversion run. Defaults to the Mojang-tuned
 * inherited values so unit tests that exercise the animation helper directly
 * don't need to set up a context.</p>
 *
 * <p>The backend's Bukkit context reads from
 * {@code BedrockDisplayOffsetsConfig} (YAML-backed); the proxy context returns
 * {@link Snapshot#defaults()} because proxy admins are not expected to tweak
 * display offsets (and there's no obvious place for a 12-field YAML on the
 * proxy plugin).</p>
 */
public final class BedrockDisplayOffsets {

    /**
     * Twelve doubles, six per perspective. See
     * {@code BedrockDisplayOffsetsConfig} for human-readable per-field docs.
     */
    public record Snapshot(
            double firstPersonBaseRotationX,
            double firstPersonBaseRotationY,
            double firstPersonBaseRotationZ,
            double firstPersonBasePositionX,
            double firstPersonBasePositionY,
            double firstPersonBasePositionZ,
            double thirdPersonBaseRotationX,
            double thirdPersonBaseRotationY,
            double thirdPersonBaseRotationZ,
            double thirdPersonBasePositionX,
            double thirdPersonBasePositionY,
            double thirdPersonBasePositionZ) {

        /**
         * Defaults that match the legacy Rainbow/RPM-inherited tuning. Used by
         * any context that doesn't want to expose a YAML knob.
         */
        public static Snapshot defaults() {
            return new Snapshot(
                    -90.0, 0.0, 0.0,
                    0.0, 12.5, 0.0,
                    90.0, 0.0, 0.0,
                    0.0, 12.5, 0.0);
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
    public static double getFirstPersonBaseRotationX() { return current.firstPersonBaseRotationX(); }
    public static double getFirstPersonBaseRotationY() { return current.firstPersonBaseRotationY(); }
    public static double getFirstPersonBaseRotationZ() { return current.firstPersonBaseRotationZ(); }
    public static double getFirstPersonBasePositionX() { return current.firstPersonBasePositionX(); }
    public static double getFirstPersonBasePositionY() { return current.firstPersonBasePositionY(); }
    public static double getFirstPersonBasePositionZ() { return current.firstPersonBasePositionZ(); }
    public static double getThirdPersonBaseRotationX() { return current.thirdPersonBaseRotationX(); }
    public static double getThirdPersonBaseRotationY() { return current.thirdPersonBaseRotationY(); }
    public static double getThirdPersonBaseRotationZ() { return current.thirdPersonBaseRotationZ(); }
    public static double getThirdPersonBasePositionX() { return current.thirdPersonBasePositionX(); }
    public static double getThirdPersonBasePositionY() { return current.thirdPersonBasePositionY(); }
    public static double getThirdPersonBasePositionZ() { return current.thirdPersonBasePositionZ(); }
}
