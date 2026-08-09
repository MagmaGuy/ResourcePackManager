package com.magmaguy.resourcepackmanager.bridge;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Durably copies the one universal RSPM artifact and validates the copied bytes
 * before publishing them at their destination.
 *
 * <p>Both the Geyser installer and the proxy update pipeline use this policy so
 * executable JAR publication cannot drift between platforms.</p>
 */
public final class UniversalPluginJarPublisher {
    private UniversalPluginJarPublisher() {
    }

    public static void copyVerified(Path source, Path target, String expectedSha256)
            throws IOException {
        if (source == null || target == null || expectedSha256 == null) {
            throw new IllegalArgumentException("source, target, and expected SHA-256 are required");
        }

        Path destination = target.toAbsolutePath().normalize();
        Path directory = destination.getParent();
        if (directory == null) {
            throw new IOException("universal JAR destination has no parent directory: " + destination);
        }

        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, ".ResourcePackManager.", ".jar.part");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }

            UniversalPluginJarInspector.Inspection copied =
                    UniversalPluginJarInspector.inspect(temporary);
            if (!expectedSha256.equals(copied.sha256())) {
                throw new IOException("copied universal JAR failed SHA-256 verification");
            }

            try {
                Files.move(temporary, destination,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException exception) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
