package com.magmaguy.resourcepackmanager.proxy;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RspmProxyConfig {

    private final int networkHttpOffset;
    private final boolean geyserExtensionAutoInstall;

    private RspmProxyConfig(int networkHttpOffset, boolean geyserExtensionAutoInstall) {
        this.networkHttpOffset = networkHttpOffset;
        this.geyserExtensionAutoInstall = geyserExtensionAutoInstall;
    }

    public int networkHttpOffset() {
        return networkHttpOffset;
    }

    public boolean geyserExtensionAutoInstall() {
        return geyserExtensionAutoInstall;
    }

    public static RspmProxyConfig loadOrCreate(Path dataDir) throws IOException {
        Files.createDirectories(dataDir);
        Path configFile = dataDir.resolve("config.yml");
        if (!Files.exists(configFile)) {
            writeDefaults(configFile);
        }
        try (Reader r = Files.newBufferedReader(configFile)) {
            // Explicitly reject Java object tags, including on proxies providing SnakeYAML 1.33.
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Map<String, Object> data = yaml.load(r);
            if (data == null) data = new LinkedHashMap<>();
            // network-key was removed as a config option in pre-release. It used to be
            // a manual paste from the backend log, but typos in the pasted value
            // silently broke the proxy↔backend link. This proxy now owns the key
            // outright: NetworkKeyAuthority resolves it from the persisted
            // `network-key` file, seeding once from plugins/floodgate/key.pem only
            // when that file happens to exist and minting a fresh key otherwise,
            // then hands it to each backend over the rspm:network plugin channel.
            // Floodgate is not required for that link.

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
            boolean extensionAutoInstall = !"false".equalsIgnoreCase(
                    String.valueOf(data.getOrDefault("geyser-extension-auto-install", true)).trim());
            return new RspmProxyConfig(offset, extensionAutoInstall);
        }
    }

    private static void writeDefaults(Path configFile) throws IOException {
        // No `network-key` entry by default, and none is ever read from this YAML.
        // The proxy owns the key: it establishes one at boot (persisted `network-key`
        // file → one-time Floodgate seed → freshly minted) and provisions every
        // backend with it over the rspm:network channel, so there is nothing to paste
        // and nothing to keep in sync by hand. Operators who used to set a
        // `network-key:` line manually in this YAML were a common source of
        // misconfiguration — typos in the pasted key silently broke the link
        // between proxy and backend.
        String yaml = """
                # ResourcePackManager proxy config.
                # There is no network key to set here. This proxy establishes its own
                # network key at boot and pushes it to each backend over the
                # rspm:network plugin channel the first time a player connects there,
                # so proxy and backends link themselves with no manual setup.
                # The key is kept in the `network-key` file next to this config. If
                # Floodgate happens to be installed on this proxy the first time
                # ResourcePackManager boots, the key is seeded once from
                # plugins/floodgate/key.pem so an existing network keeps its identity
                # across the upgrade — after that, key.pem is never read again.
                # Floodgate is NOT required for the proxy↔backend link (a Java-only
                # network needs none); it is only needed for Bedrock players to
                # authenticate.

                # Fallback offset added to each backend's Minecraft port to derive the
                # HTTP port this proxy will hit for /bedrock.zip and /mappings.json
                # before that backend has announced its exact ResourcePackManager HTTP
                # port. Default 1.
                # Why so small: shared / managed Minecraft hosting (Pterodactyl panels,
                # etc.) allocates a narrow port range per container — offset 100 lands
                # outside the range and the host firewall silently drops the request.
                # Offset 1 fits even tight allocations. In normal operation the backend
                # announces the port it actually bound, so this is only a startup/failure
                # fallback.
                #
                # Note: if a backend has rcon enabled on MC port + 1, choose 2 or 3 to
                # avoid a port collision.
                network-http-offset-v2: 1

                # Installs and updates this universal ResourcePackManager.jar in Geyser's
                # extensions folder for custom Bedrock entity models. Set false to prevent
                # future installation/staging; remove existing extension JARs while Geyser
                # is stopped if the bridge must remain disabled.
                geyser-extension-auto-install: true
                """;
        Files.writeString(configFile, yaml);
    }
}
