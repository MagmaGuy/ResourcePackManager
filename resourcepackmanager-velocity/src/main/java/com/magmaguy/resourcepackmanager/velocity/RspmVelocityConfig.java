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

    private RspmVelocityConfig(String networkKey, boolean forceResourcePack, int selfHostPort, String selfHostExternalHost) {
        this.networkKey = networkKey;
        this.forceResourcePack = forceResourcePack;
        this.selfHostPort = selfHostPort;
        this.selfHostExternalHost = selfHostExternalHost;
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
                    (String) data.getOrDefault("self-host-external-host", "")
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

                # Port for the self-host HTTP fallback (used when uploading the merged pack
                # to magmaguy.com fails). You must open this port in your firewall manually.
                self-host-port: 25567

                # Public hostname/IP clients use to reach the self-host server. Leave empty
                # for auto-detect (best-effort).
                self-host-external-host: ""
                """;
        Files.writeString(configFile, yaml);
    }
}
