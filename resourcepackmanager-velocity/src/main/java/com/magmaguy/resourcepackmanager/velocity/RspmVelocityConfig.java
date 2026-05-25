package com.magmaguy.resourcepackmanager.velocity;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

final class RspmVelocityConfig {

    private final String networkKey;
    private final boolean forceResourcePack;
    private final int selfHostPort;
    private final String selfHostExternalHost;
    private final int backendMetadataPort;

    private RspmVelocityConfig(String networkKey,
                               boolean forceResourcePack,
                               int selfHostPort,
                               String selfHostExternalHost,
                               int backendMetadataPort) {
        this.networkKey = networkKey;
        this.forceResourcePack = forceResourcePack;
        this.selfHostPort = selfHostPort;
        this.selfHostExternalHost = selfHostExternalHost;
        this.backendMetadataPort = backendMetadataPort;
    }

    String networkKey() {
        return networkKey;
    }

    boolean forceResourcePack() {
        return forceResourcePack;
    }

    int selfHostPort() {
        return selfHostPort;
    }

    String selfHostExternalHost() {
        return selfHostExternalHost;
    }

    int backendMetadataPort() {
        return backendMetadataPort;
    }

    static RspmVelocityConfig loadOrCreate(Path dataDir) throws IOException {
        Files.createDirectories(dataDir);
        Path configFile = dataDir.resolve("config.yml");
        if (!Files.exists(configFile)) {
            writeDefaults(configFile);
        }
        try (Reader r = Files.newBufferedReader(configFile)) {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(r);
            if (data == null) data = new LinkedHashMap<>();
            return new RspmVelocityConfig(
                    (String) data.getOrDefault("network-key", ""),
                    (Boolean) data.getOrDefault("force-resource-pack", false),
                    ((Number) data.getOrDefault("self-host-port", 25567)).intValue(),
                    (String) data.getOrDefault("self-host-external-host", ""),
                    ((Number) data.getOrDefault("backend-metadata-port", 25567)).intValue()
            );
        }
    }

    private static void writeDefaults(Path configFile) throws IOException {
        String yaml = """
                # ResourcePackManager-Velocity config.
                # Paste the network-key your backend RPM logged on startup.

                network-key: ""

                # Force clients to accept the pack (kick on decline). Default: false.
                force-resource-pack: false

                # Port for the self-host HTTP server (serves the proxy-merged pack to Bedrock
                # via Geyser). You must open this port in your firewall manually.
                self-host-port: 25567

                # Public hostname/IP clients use to reach the self-host server. Leave empty
                # for auto-detect (best-effort).
                self-host-external-host: ""

                # TCP port the proxy will hit on every backend to fetch /.rspm-pack-info.json.
                # Must match the backend RPM's `selfHostPort` config (also 25567 by default).
                # Override here if you run RPM on a non-default port on the backends.
                backend-metadata-port: 25567
                """;
        Files.writeString(configFile, yaml);
    }
}
