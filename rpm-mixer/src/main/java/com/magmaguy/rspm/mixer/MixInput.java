package com.magmaguy.rspm.mixer;

import java.io.File;
import java.util.List;

/**
 * Input parameters for {@link MixEngine#run}.
 *
 * <p>{@code collisionLogDir} is intentionally separate from {@code outputDir} so the
 * collision log can be parked outside the staging area. The Bukkit wrapper writes
 * it to the plugin's data folder root (matching the legacy
 * {@code dataFolder/collision_log.txt} path) while keeping the zip + unzipped
 * staging in {@code dataFolder/output/}.</p>
 */
public record MixInput(
    List<File> orderedPacks,   // zip files OR directories, priority order (first wins collisions)
    File workingDir,           // scratch space for unzip + assembly
    File outputDir,            // where final zip + per-pack staging dirs land
    File collisionLogDir,      // where collision_log.txt is written (when writeCollisionLog == true)
    String outputName,         // file name without extension, e.g. "ResourcePackManager_RSP"
    boolean writeCollisionLog
) {}
