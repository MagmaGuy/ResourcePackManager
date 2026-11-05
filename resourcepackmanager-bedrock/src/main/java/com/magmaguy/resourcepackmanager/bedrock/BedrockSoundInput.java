package com.magmaguy.resourcepackmanager.bedrock;

import java.io.File;
import java.util.Map;

/**
 * Where the Bedrock converter reads custom sounds from, when that differs from the pack it converts.
 *
 * @param packDir     pack root whose {@code assets/<ns>/sounds.json} and {@code assets/<ns>/sounds/**}
 *                    the sound conversion reads; {@code null} means the converted pack itself
 * @param fileSources source pack name per Java sound file path ({@code assets/<ns>/sounds/<path>.ogg}),
 *                    used to report what each source pack added
 */
public record BedrockSoundInput(File packDir, Map<String, String> fileSources) {
    public BedrockSoundInput {
        fileSources = fileSources == null ? Map.of() : Map.copyOf(fileSources);
    }
}
