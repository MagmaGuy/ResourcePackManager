package com.magmaguy.resourcepackmanager.mixer.bedrock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * One content identity definition for exact Bedrock texture reuse.
 *
 * <p>The extension remains part of the identity because Bedrock loaders can
 * interpret identical bytes differently when the referenced file type differs.</p>
 */
public record ExactTextureFingerprint(String extension, long size, String sha256) {
    public static ExactTextureFingerprint of(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            return new ExactTextureFingerprint(
                    extension(file), Files.size(file), HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot).toLowerCase(Locale.ROOT) : "";
    }
}
