package com.magmaguy.rspm.mixer;

import java.io.File;
import java.util.List;

/**
 * Result of a {@link MixEngine#run(MixInput)} call.
 *
 * <p>{@link #mergedDir} is the still-extant unzipped staging folder
 * ({@code <outputDir>/<outputName>/}). The engine deliberately leaves it on disk
 * after zipping so platform wrappers can run post-processing against the
 * filesystem layout — e.g. Bedrock conversion or copying the unzipped pack to
 * an external reroute target. Wrappers are responsible for deleting it when
 * they are done.</p>
 */
public record MixOutput(
    File mergedZip,
    File mergedDir,
    String sha1Hex,
    byte[] sha1Bytes,
    List<String> collisionLog
) {}
