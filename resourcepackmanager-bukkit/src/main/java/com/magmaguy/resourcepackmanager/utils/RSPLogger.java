package com.magmaguy.resourcepackmanager.utils;

import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.config.DefaultConfig;

/**
 * Logging helper that separates "an admin needs to know this" from "this is how the sausage is made".
 * <p>
 * Preparing a resource pack is a long pipeline — stage each contributing plugin's pack, merge the
 * clusters, wait for them to stop changing, mix, convert for Bedrock, then hand the result to a
 * host. Narrating each of those steps produced roughly sixty console lines per startup, which buries
 * the only two things anyone actually reads: whether players are going to get the pack, and what to
 * do if they are not.
 * <p>
 * So step-by-step narration goes through {@link #detail}, which stays silent unless the admin turns
 * on verboseLogging in the config. Only the final outcome and genuine problems print by default.
 */
public final class RSPLogger {

    private RSPLogger() {
    }

    /**
     * Progress narration. Silent unless verboseLogging is enabled.
     */
    public static void detail(String message) {
        if (DefaultConfig.isVerboseLogging()) Logger.info(message);
    }

    /**
     * The one line that says how it turned out. Always printed.
     */
    public static void outcome(String message) {
        Logger.info(message);
    }

}
