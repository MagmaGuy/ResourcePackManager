package com.magmaguy.resourcepackmanager.mixer.engine;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * Result of a {@link MixEngine#run(MixInput)} call.
 *
 * <p>{@link #mergedDir} is the still-extant unzipped staging folder
 * ({@code <outputDir>/<outputName>/}). The engine deliberately leaves it on disk
 * after zipping so platform wrappers can run post-processing against the
 * filesystem layout — e.g. Bedrock conversion or copying the unzipped pack to
 * an external reroute target. Wrappers are responsible for deleting it when
 * they are done.</p>
 *
 * <p>{@link #bedrockInputDir} is what the Bedrock converter should read. It is
 * {@code mergedDir} itself unless {@link MixInput#bedrockExcludedPacks()} left
 * packs out, in which case it is a second merged folder without them that the
 * wrapper also deletes.</p>
 *
 * <p>{@link #bedrockSoundInputDir} is where the Bedrock converter reads custom sounds. It is
 * {@code bedrockInputDir} unless {@link MixInput#bedrockSoundExcludedPacks()} left packs' sounds
 * out, in which case it is a third folder holding only the sounds.json files and sounds/ trees of
 * the remaining packs, which the wrapper also deletes. {@link #bedrockSoundSources} names the
 * source pack of each sound file ({@code assets/<ns>/sounds/<path>} to pack file name) when
 * {@link MixInput#bedrockSounds()} is set, and is empty otherwise.</p>
 */
public record MixOutput(
    File mergedZip,
    File mergedDir,
    String sha1Hex,
    byte[] sha1Bytes,
    List<String> collisionLog,
    File bedrockInputDir,
    File bedrockSoundInputDir,
    Map<String, String> bedrockSoundSources
) {
    public MixOutput {
        if (bedrockSoundInputDir == null) bedrockSoundInputDir = bedrockInputDir;
        bedrockSoundSources = bedrockSoundSources == null ? Map.of() : Map.copyOf(bedrockSoundSources);
    }
}
