package com.magmaguy.resourcepackmanager.mixer.engine;

import java.io.File;
import java.util.List;
import java.util.Set;

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
    File outputDir,            // where final zip + per-pack staging dirs land
    File collisionLogDir,      // where collision_log.txt is written (when writeCollisionLog == true)
    String outputName,         // file name without extension, e.g. "ResourcePackManager_RSP"
    boolean writeCollisionLog,
    String reusableArchiveSha1, // optional verified prior Java output; rebuild expanded converter input only
    Set<File> bedrockExcludedPacks, // members of orderedPacks left out of the Bedrock converter's input
    boolean bedrockSounds,          // the Bedrock converter carries custom sounds over
    Set<File> bedrockSoundExcludedPacks // members of orderedPacks whose sounds the Bedrock converter leaves out
) {
    public MixInput {
        bedrockExcludedPacks = bedrockExcludedPacks == null ? Set.of() : Set.copyOf(bedrockExcludedPacks);
        bedrockSoundExcludedPacks = bedrockSoundExcludedPacks == null || !bedrockSounds
                ? Set.of() : Set.copyOf(bedrockSoundExcludedPacks);
    }

    public MixInput(List<File> orderedPacks, File outputDir, File collisionLogDir,
                    String outputName, boolean writeCollisionLog, String reusableArchiveSha1,
                    Set<File> bedrockExcludedPacks) {
        this(orderedPacks, outputDir, collisionLogDir, outputName, writeCollisionLog,
                reusableArchiveSha1, bedrockExcludedPacks, false, Set.of());
    }

    public MixInput(List<File> orderedPacks, File outputDir,
                    File collisionLogDir, String outputName, boolean writeCollisionLog) {
        this(orderedPacks, outputDir, collisionLogDir, outputName, writeCollisionLog, null, Set.of(),
                false, Set.of());
    }
}
