package com.magmaguy.resourcepackmanager.proxy;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Installs the bundled Geyser extension that translates backend custom-entity
 * bridge messages into Bedrock custom entities on proxy-hosted Geyser setups.
 */
public final class GeyserBridgeExtensionInstaller {
    private static final String BRIDGE_RESOURCE = "geyser-extension/ResourcePackManager-GeyserBridge.jar";
    private static final String BRIDGE_FILE_NAME = "ResourcePackManager-GeyserBridge.jar";

    private GeyserBridgeExtensionInstaller() {
    }

    public static void install(File geyserPluginDir, ProxyLogger logger) {
        if (geyserPluginDir == null) {
            return;
        }

        Path extensionsDirectory = geyserPluginDir.toPath().resolve("extensions");
        Path extensionFile = extensionsDirectory.resolve(BRIDGE_FILE_NAME);
        try (InputStream inputStream = GeyserBridgeExtensionInstaller.class
                .getClassLoader()
                .getResourceAsStream(BRIDGE_RESOURCE)) {
            if (inputStream == null) {
                logger.warn("Bundled RSPM Geyser bridge extension jar is missing from the plugin jar.");
                return;
            }

            byte[] bundledBytes = inputStream.readAllBytes();
            Files.createDirectories(extensionsDirectory);
            boolean changed = !Files.isRegularFile(extensionFile)
                    || !Arrays.equals(sha256(extensionFile), sha256(bundledBytes));
            if (!changed) {
                return;
            }

            Files.write(extensionFile, bundledBytes);
            logger.warn("Installed or updated " + BRIDGE_FILE_NAME + " in "
                    + extensionsDirectory.toAbsolutePath()
                    + ". Restart the proxy so Geyser loads the RSPM custom entity bridge before Bedrock players join.");
        } catch (IOException | NoSuchAlgorithmException exception) {
            logger.warn("Failed to install RSPM Geyser bridge extension: " + exception.getMessage(), exception);
        }
    }

    private static byte[] sha256(Path path) throws IOException, NoSuchAlgorithmException {
        return sha256(Files.readAllBytes(path));
    }

    private static byte[] sha256(byte[] bytes) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return digest.digest(bytes);
    }
}
