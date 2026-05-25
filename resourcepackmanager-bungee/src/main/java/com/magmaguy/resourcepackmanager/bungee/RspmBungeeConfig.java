package com.magmaguy.resourcepackmanager.bungee;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

final class RspmBungeeConfig {

    private final String networkKey;
    private final boolean forceResourcePack;
    private final int networkHttpOffset;

    private RspmBungeeConfig(String networkKey,
                             boolean forceResourcePack,
                             int networkHttpOffset) {
        this.networkKey = networkKey;
        this.forceResourcePack = forceResourcePack;
        this.networkHttpOffset = networkHttpOffset;
    }

    String networkKey() {
        return networkKey;
    }

    boolean forceResourcePack() {
        return forceResourcePack;
    }

    int networkHttpOffset() {
        return networkHttpOffset;
    }

    static RspmBungeeConfig loadOrCreate(Path dataDir) throws IOException {
        Files.createDirectories(dataDir);
        Path configFile = dataDir.resolve("config.yml");
        if (!Files.exists(configFile)) {
            writeDefaults(configFile);
        }
        try (Reader r = Files.newBufferedReader(configFile)) {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(r);
            if (data == null) data = new LinkedHashMap<>();
            // New canonical key: network-http-offset (added to each backend's MC port
            // to derive its HTTP port). Default 100. Older configs that used a fixed
            // backend-http-port / backend-metadata-port / self-host-port are ignored —
            // the new per-backend derivation supersedes them. Admins who explicitly
            // set those keys before will get the default offset and likely need to
            // re-bind any non-default ports via the BACKEND's selfHostPort config.
            int offset = 100;
            if (data.containsKey("network-http-offset")) {
                offset = ((Number) data.get("network-http-offset")).intValue();
            }
            return new RspmBungeeConfig(
                    (String) data.getOrDefault("network-key", ""),
                    (Boolean) data.getOrDefault("force-resource-pack", false),
                    offset
            );
        }
    }

    private static void writeDefaults(Path configFile) throws IOException {
        String yaml = """
                # ResourcePackManager-BungeeCord config.
                # Paste the network-key your backend RPM logged on startup.

                network-key: ""

                # Force clients to accept the pack (kick on decline). Default: false.
                force-resource-pack: false

                # Offset added to each backend's Minecraft port to derive the HTTP port
                # this proxy will hit for /bedrock.zip and /mappings.json. Default 100
                # => MC 25565 -> HTTP 25665, MC 25671 -> HTTP 25771, etc. Must match
                # each backend's `networkHttpOffset` config. Admins rarely need to
                # change this — bump it only if 100 collides with something on the
                # backend host.
                network-http-offset: 100
                """;
        Files.writeString(configFile, yaml);
    }
}
