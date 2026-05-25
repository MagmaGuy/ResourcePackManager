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

    public static void info(String message) {
        sink.info(message);
    }

    public static void warn(String message) {
        sink.warn(message);
    }
}
