package com.magmaguy.resourcepackmanager.bedrock.util;

import com.magmaguy.resourcepackmanager.mixer.bedrock.ExactTextureFingerprint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Run-scoped cache that lets generated models share a byte-identical texture
 * before their JSON files are emitted. This avoids writing thousands of alias
 * references only to rewrite them during final pack optimization.
 */
public final class ExactTextureFileCache {
    private final Map<ExactTextureFingerprint, CanonicalTexture> canonicalByFingerprint =
            new HashMap<>();

    public CanonicalTexture canonicalize(Path textureFile, String packReference) throws IOException {
        if (textureFile == null || !Files.isRegularFile(textureFile)) {
            throw new IOException("Texture file is missing: " + textureFile);
        }
        if (packReference == null || packReference.isBlank()) {
            throw new IOException("Texture pack reference is missing for " + textureFile);
        }

        ExactTextureFingerprint fingerprint = ExactTextureFingerprint.of(textureFile);
        CanonicalTexture candidate = new CanonicalTexture(textureFile, packReference);
        CanonicalTexture canonical = canonicalByFingerprint.putIfAbsent(fingerprint, candidate);
        if (canonical == null) return candidate;

        // The canonical file is already complete. A deletion failure only leaves an
        // unreferenced extra file for the final conservative optimizer to remove.
        Files.deleteIfExists(textureFile);
        return canonical;
    }

    public record CanonicalTexture(Path file, String packReference) {
    }
}
