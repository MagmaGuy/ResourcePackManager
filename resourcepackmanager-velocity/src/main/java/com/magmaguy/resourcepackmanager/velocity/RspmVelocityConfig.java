package com.magmaguy.resourcepackmanager.velocity;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

final class RspmVelocityConfig {

    private final boolean forceResourcePack;
    private final int networkHttpOffset;

    private RspmVelocityConfig(boolean forceResourcePack,
                               int networkHttpOffset) {
        this.forceResourcePack = forceResourcePack;
        this.networkHttpOffset = networkHttpOffset;
    }

    boolean forceResourcePack() {
        return forceResourcePack;
    }

    int networkHttpOffset() {
        return networkHttpOffset;
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
            // network-key was removed as a config option in pre-release. It used to be
            // a manual paste from the backend log, but typos in the pasted value
            // silently broke the proxy↔backend link. The key is now derived solely
            // from plugins/floodgate/key.pem on this proxy — Floodgate already
            // requires that file to be the same across the whole network for Bedrock
            // auth, so the derived value matches every backend automatically.

            // Versioned offset key. The v1 key was `network-http-offset` with default
            // 100; that default failed on shared/managed hosting where each container
            // gets a narrow port band (offset 100 landed outside the band and the host
            // firewall silently blocked the HTTP port). v2 ships with default 1.
            // Operators upgrading from v1 get the new default automatically — the v1
            // key is intentionally not read. See DefaultConfig.java in the backend
            // module for the full rationale.
            int offset = 1;
            if (data.containsKey("network-http-offset-v2")) {
                offset = ((Number) data.get("network-http-offset-v2")).intValue();
            }
            return new RspmVelocityConfig(
                    (Boolean) data.getOrDefault("force-resource-pack", false),
                    offset
            );
        }
    }

    private static void writeDefaults(Path configFile) throws IOException {
        // No `network-key` entry by default. The key is auto-derived from
        // `plugins/floodgate/key.pem` on this proxy at boot — that's the same
        // key.pem every backend uses (Floodgate REQUIRES it to be shared for
        // Bedrock players to connect), so the resulting network-key matches
        // every backend's automatically. Operators who set a `network-key:` line
        // manually in this YAML were a common source of misconfiguration —
        // typos in the pasted key silently broke the link between proxy and
        // backend. The resolution code still honors the key if present
        // (advanced override), but the default config no longer suggests it.
        String yaml = """
                # ResourcePackManager-Velocity config.
                # The network-key is auto-derived from plugins/floodgate/key.pem on this
                # proxy — make sure Floodgate is installed (it must be, for Bedrock
                # players to reach the proxy) and that the same key.pem is shared with
                # every backend (Floodgate requires this anyway). No manual setup needed.

                # Force clients to accept the pack (kick on decline). Default: false.
                force-resource-pack: false

                # Offset added to each backend's Minecraft port to derive the HTTP port
                # this proxy will hit for /bedrock.zip and /mappings.json. Default 1.
                # Why so small: shared / managed Minecraft hosting (Pterodactyl panels,
                # etc.) allocates a narrow port range per container — offset 100 lands
                # outside the range and the host firewall silently drops the request.
                # Offset 1 fits even tight allocations. Must match each backend's
                # `networkHttpOffset-v2`. Bump this only if you fully control the host's
                # firewall AND want a larger gap between MC port and HTTP port.
                #
                # Note: if a backend has rcon enabled on MC port + 1, choose 2 or 3 to
                # avoid a port collision.
                network-http-offset-v2: 1
                """;
        Files.writeString(configFile, yaml);
    }
}
