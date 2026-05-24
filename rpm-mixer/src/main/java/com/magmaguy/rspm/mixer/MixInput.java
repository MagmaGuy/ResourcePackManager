package com.magmaguy.rspm.mixer;

import java.io.File;
import java.util.List;

public record MixInput(
    List<File> orderedPacks,   // zip files OR directories, priority order (first wins collisions)
    File workingDir,           // scratch space for unzip + assembly
    File outputDir,            // where final zip + collision log land
    String outputName,         // file name without extension, e.g. "ResourcePackManager_RSP"
    boolean writeCollisionLog
) {}
