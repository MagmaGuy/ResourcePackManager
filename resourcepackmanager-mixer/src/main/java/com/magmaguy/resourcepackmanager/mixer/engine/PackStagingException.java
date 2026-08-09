package com.magmaguy.resourcepackmanager.mixer.engine;

import java.io.File;
import java.io.IOException;

/**
 * Raised when one specific input pack cannot be staged.
 *
 * <p>Carries the offending file so platform callers can act on that pack
 * directly instead of parsing the message text. Without the reference the only
 * way to identify the culprit is string matching, which is exactly what a
 * quarantine decision must not depend on.</p>
 */
public class PackStagingException extends IOException {
    private final File pack;

    public PackStagingException(File pack, String message, Throwable cause) {
        super(message, cause);
        this.pack = pack;
    }

    /**
     * @return the pack that failed to stage; never {@code null}
     */
    public File getPack() {
        return pack;
    }
}
