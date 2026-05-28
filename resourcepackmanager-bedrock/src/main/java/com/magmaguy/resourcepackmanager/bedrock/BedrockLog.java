package com.magmaguy.resourcepackmanager.bedrock;

import com.magmaguy.resourcepackmanager.mixer.engine.MixerLogger;

/**
 * Package-wide static log facade used by every converter helper class. Set once
 * at the start of {@link BedrockConversion#generate} from the
 * {@link BedrockConverterContext}'s {@link MixerLogger}, and read from there by
 * every utility (geometry converter, scanner, stitcher, …).
 *
 * <p>Why a static slot rather than threading a logger parameter through every
 * helper: there are 20+ classes in this module and every helper logs in a
 * dozen spots. Threading a {@code MixerLogger} parameter through all of them
 * would touch ~150 call sites for zero behavioral benefit. The conversion
 * pipeline is not concurrent — exactly one {@code BedrockConversion#generate}
 * call runs at a time on the mixer's scheduler thread — so a single static
 * sink is safe.</p>
 *
 * <p>Defaults to a no-op sink before/after a generate call, so unit tests that
 * exercise a single helper class don't crash on a missing logger.</p>
 */
public final class BedrockLog {

    private static final MixerLogger NO_OP = new MixerLogger() {
        @Override public void info(String message) {}
        @Override public void warn(String message) {}
        @Override public void collision(String message) {}
    };

    private static volatile MixerLogger sink = NO_OP;

    /**
     * Debug flag: when {@code false} (default), {@link #debug(String)} is a no-op.
     * Set once at conversion start from
     * {@link BedrockConverterContext#isBedrockConverterDebug()} so per-cycle config
     * changes take effect on the next mix without a server restart. Persists across
     * {@link #set(MixerLogger)} calls so platform code outside the conversion run
     * (e.g. {@link com.magmaguy.resourcepackmanager.bedrock.BedrockConversion#deployPreviousMappingsIfNeeded})
     * can still gate its own logs on the same toggle.
     */
    private static volatile boolean debugEnabled = false;

    private BedrockLog() {}

    /**
     * Install the platform's logger for the duration of a conversion run. Passing
     * {@code null} resets to the no-op sink (used in the {@code finally} block of
     * {@link BedrockConversion#generate} so a subsequent test/standalone use of a
     * helper doesn't silently log into a closed Bukkit/Velocity logger).
     */
    public static void set(MixerLogger logger) {
        sink = (logger != null) ? logger : NO_OP;
    }

    /**
     * Enable or disable {@link #debug(String)} output. Independent of the sink so
     * the debug toggle can be applied once at plugin boot from config and stay in
     * effect across multiple {@code BedrockConversion.generate} cycles. Pre-boot
     * default is {@code false}, so debug calls never spam the console unless an
     * operator explicitly opts in.
     */
    public static void setDebug(boolean enabled) {
        debugEnabled = enabled;
    }

    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    public static void info(String message) {
        sink.info(message);
    }

    public static void warn(String message) {
        sink.warn(message);
    }

    /**
     * Per-item / per-bone / per-attachable progress and informational messages.
     * When Geyser is installed, these fire dozens to hundreds of times per RSPM
     * pack mix and made the console look like the converter was erroring out
     * even on a clean run (see RSPM ticket: "RSPM is very noisy in console when
     * used with Geyser"). Routes through the same sink as {@link #info(String)}
     * but only when {@link #setDebug(boolean)} has been called with {@code true}.
     * Logged at info level on the underlying sink so the message — when emitted —
     * is not mistaken for an actual warning.
     */
    public static void debug(String message) {
        if (debugEnabled) sink.info(message);
    }
}
