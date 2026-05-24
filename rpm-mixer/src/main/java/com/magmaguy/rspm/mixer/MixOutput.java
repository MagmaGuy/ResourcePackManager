package com.magmaguy.rspm.mixer;

import java.io.File;
import java.util.List;

public record MixOutput(
    File mergedZip,
    String sha1Hex,
    byte[] sha1Bytes,
    List<String> collisionLog
) {}
