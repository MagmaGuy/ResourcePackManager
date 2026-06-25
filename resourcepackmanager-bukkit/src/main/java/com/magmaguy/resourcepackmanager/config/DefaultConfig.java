package com.magmaguy.resourcepackmanager.config;

import com.magmaguy.magmacore.config.ConfigurationEngine;
import com.magmaguy.magmacore.config.ConfigurationFile;
import com.magmaguy.magmacore.nightbreak.NightbreakPluginUpdater;
import lombok.Getter;

import java.util.List;

public class DefaultConfig extends ConfigurationFile {

    @Getter
    private static List<String> priorityOrder;
    @Getter
    private static boolean autoHost;
    @Getter
    private static boolean forceResourcePack;
    @Getter
    private static String resourcePackPrompt;
    @Getter
    private static String resourcePackRerouting;
    @Getter
    private static boolean bedrockConversionEnabled = true;
    @Getter
    private static boolean bedrockAutoDeployToGeyser = true;
    @Getter
    private static String bedrockGeyserFolder = "";
    @Getter
    private static boolean bedrockConverterDebug = false;
    @Getter
    private static boolean selfHostEnabled = true;
    @Getter
    private static int selfHostPort = -1;
    @Getter
    private static int networkHttpOffset = 100;
    @Getter
    private static String selfHostExternalHost = "";
    @Getter
    private static boolean selfHostForce = false;
    @Getter
    private static boolean preferSelfHost = true;
    @Getter
    private static boolean autoDownloadPluginUpdates;


    public DefaultConfig() {
        super("config.yml");
    }

    @Override
    public void initializeValues() {
        autoDownloadPluginUpdates = NightbreakPluginUpdater.setAutoDownloadConfigDefault(fileConfiguration);

        priorityOrder = ConfigurationEngine.setList(
                List.of(
                        "Sets the list, from highest priority (top) to lowest priority (bottom), in which the resource" +
                                " packs will automatically resolve merge conflicts.",
                        "The defaults use plugin names. If you manually added your own resource pack in the mixer folder to be merged in, add its exact filename, including .zip in the name"),
                fileConfiguration, "priorityOrder",
                List.of(
                        "ResourcePackManager",
                        "EliteMobs",
                        "FreeMinecraftModels",
                        "ModelEngine",
                        "Nova",
                        "ItemsAdder",
                        "Oraxen",
                        "BetterHUD",
                        "ValhallaMMO",
                        "MMOInventory",
                        "vane-core",
                        "RealisticSurvival"));

        autoHost = ConfigurationEngine.setBoolean(
                List.of("Automatically host the resource pack on MagmaGuy's servers",
                        "These servers cost money to keep running. There is no guarantee this will be an option forever."),
                fileConfiguration, "autoHost", true);

        forceResourcePack = ConfigurationEngine.setBoolean(
                List.of("Sets whether the resource pack use will be forced to clients"),
                fileConfiguration, "forceResourcePack", false);
        resourcePackPrompt = ConfigurationEngine.setString(
                List.of("Sets whether the resource pack use will be forced to clients"),
                fileConfiguration, "resourcePackPrompt", "Use recommended resource pack?");
        resourcePackRerouting = ConfigurationEngine.setString(
                List.of(
                        "OPTIONAL: Copy the merged directory to a custom directory location. Useful for unusual setups, like people trying to host with a different plugin.",
                        "If you are hosting with a different plugin make sure to disable Auto-hosting here!",
                        "This will use the plugin directory as the base directory.",
                        "As an example, if you wanted to target ResourcePackManager's output folder, you'd do:",
                        "ResourcePackManage/output",
                        "If you don't know what any of what is written here means, just don't touch this setting!"),
                fileConfiguration, "resourcePackRerouting", "");

        bedrockConversionEnabled = ConfigurationEngine.setBoolean(
                List.of("Enables automatic conversion of the merged Java resource pack to a Bedrock resource pack for GeyserMC."),
                fileConfiguration, "bedrockConversionEnabled", true);
        bedrockAutoDeployToGeyser = ConfigurationEngine.setBoolean(
                List.of("Automatically deploy the converted Bedrock resource pack to the Geyser packs folder."),
                fileConfiguration, "bedrockAutoDeployToGeyser", true);
        bedrockGeyserFolder = ConfigurationEngine.setString(
                List.of("Path to the Geyser packs folder. Leave empty to auto-detect."),
                fileConfiguration, "bedrockGeyserFolder", "");

        bedrockConverterDebug = ConfigurationEngine.setBoolean(
                List.of(
                        "Enables verbose per-item / per-bone progress logging from the Bedrock conversion pipeline.",
                        "Default false — when Geyser is installed the converter walks every items definition in the",
                        "merged Java pack and previously emitted dozens to hundreds of progress and 'unsupported",
                        "Java condition X' lines per /reload, which looked alarming despite indicating normal",
                        "operation. Leave this off for clean console output; flip on if you are debugging a Bedrock",
                        "conversion issue and want to see every per-item / per-attachable / per-mapping step."),
                fileConfiguration, "bedrockConverterDebug", false);

        // network-key is intentionally NOT a config option. It's auto-derived
        // from plugins/floodgate/key.pem on every backend and on the proxy.
        // Floodgate requires the same key.pem across the network for Bedrock
        // players to connect at all, so the derived value matches everywhere
        // automatically. The old "paste this key into the proxy config" flow
        // was a major source of misconfiguration (typos silently broke the
        // proxy↔backend link) and is removed entirely. See NetworkMode#getNetworkKey.

        selfHostEnabled = ConfigurationEngine.setBoolean(
                List.of(
                        "Fallback to a local HTTP server when uploading the pack to magmaguy.com fails.",
                        "When enabled (default), if the upload fails for any reason (server down, file too large, etc.)",
                        "RPM will start a local HTTP server on `selfHostPort` and use that URL to push the pack to players.",
                        "You are responsible for opening the firewall port; RPM does not probe reachability.",
                        "Interaction with selfHostForce: forced=true always self-hosts regardless of this flag."),
                fileConfiguration, "selfHostEnabled", true);

        selfHostPort = ConfigurationEngine.setInt(
                List.of(
                        "Port for the self-host HTTP server.",
                        "-1 (default) = auto-derive: HTTP port = Minecraft server port + networkHttpOffset-v2.",
                        "  This makes single-host networks (multiple backends on localhost) auto-stagger:",
                        "  each backend already has a unique MC port, so each gets a unique HTTP port,",
                        "  with zero admin configuration.",
                        "Set to any positive value to force an explicit port (legacy behaviour, default was 25567)."),
                fileConfiguration, "selfHostPort", -1);

        // Versioned key — `networkHttpOffset` (no suffix) was the v1 key with default 100.
        // That default broke on shared/managed Minecraft hosting (alienhost.me, Pterodactyl
        // tenants, etc.) where each game container gets only ~4–10 consecutive ports.
        // MC + 100 fell outside the container's allocated port range, the HTTP server
        // bound internally but the host firewall dropped external traffic, and the proxy
        // got a silent CONNECT_FAILED forever. v2 ships with default 1 so MC + 1 stays
        // well inside even the narrowest container allocations. Self-hosted admins who
        // have a real reason to use a bigger offset just set this knob; nothing forces
        // them to v1's 100.
        //
        // The old `networkHttpOffset` key is intentionally NOT read here — operators
        // who upgrade from v1 get the v2 default written to config.yml on next boot.
        // The dead v1 key sits in their config as a harmless artifact until they choose
        // to clean it up.
        networkHttpOffset = ConfigurationEngine.setInt(
                List.of(
                        "Fallback offset added to the Minecraft server port to derive the HTTP port when selfHostPort = -1.",
                        "Default 1 => MC 25565 -> HTTP 25566, MC 25584 -> HTTP 25585, etc.",
                        "Why 1: most shared / managed Minecraft hosting (Pterodactyl-based panels, etc.)",
                        "  allocates a narrow port range per container (often only 4–10 ports). Larger",
                        "  offsets land outside the range and the host firewall silently blocks the HTTP",
                        "  port. Offset 1 fits even tight allocations. Self-hosted admins with full port",
                        "  control can bump this to any value; proxies receive the exact bound HTTP port",
                        "  through RSPM's automatic backend endpoint announcement.",
                        "Note: if your host enables rcon by default on MC port + 1, choose 2 or 3 instead",
                        "  to avoid a collision. Check server.properties `rcon.port=`."),
                fileConfiguration, "networkHttpOffset-v2", 1);

        selfHostExternalHost = ConfigurationEngine.setString(
                List.of(
                        "Public hostname or IP that clients use to reach your self-host server.",
                        "Leave empty to auto-detect, in priority order:",
                        "  1. api.ipify.org / checkip.amazonaws.com (returns the public IPv4 of THIS host)",
                        "  2. Bukkit.getIp() (the server's bind address — usually 0.0.0.0 or a LAN IP)",
                        "  3. InetAddress.getLocalHost() (best-effort)",
                        "  4. localhost (last-resort fallback; clients outside the box won't reach this)",
                        "If preferSelfHost=true (the default) and auto-detection lands on a non-routable",
                        "address (10.*, 172.16-31.*, 192.168.*, 127.*) the reachability probe will fail",
                        "and the plugin will switch to remote hosting. Set this explicitly to your public",
                        "hostname (e.g. play.example.com) for the most reliable self-host setup."),
                fileConfiguration, "selfHostExternalHost", "");

        selfHostForce = ConfigurationEngine.setBoolean(
                List.of(
                        "Skip ALL other delivery paths and force self-hosting.",
                        "Mainly for testing the self-host path. Bypasses both the reachability probe AND the remote upload.",
                        "When true, this overrides preferSelfHost and selfHostEnabled (self-hosting always happens)."),
                fileConfiguration, "selfHostForce", false);

        preferSelfHost = ConfigurationEngine.setBoolean(
                List.of(
                        "Default (true): try self-hosting FIRST, then run two sanity checks before",
                        "committing to it:",
                        "  1. The resolved external host must NOT be RFC1918 / loopback / link-local",
                        "     (i.e. clients on the internet have at least a chance of reaching it).",
                        "  2. A localhost HEAD request to the self-host HTTP server must return 200",
                        "     with a non-empty body (catches port collisions, missing pack file,",
                        "     misconfigured routes).",
                        "If both pass: keep self-hosting (zero bandwidth cost to magmaguy.com, lower",
                        "latency for clients close to your server).",
                        "If either fails: tear down the self-host server and fall back to uploading",
                        "the pack to magmaguy.com's CDN (the legacy behaviour).",
                        "",
                        "Set to false to use the legacy order unconditionally: try magmaguy.com upload",
                        "first, fall back to self-host only on upload failure. Most operators should",
                        "leave this at true — it's kinder to magmaguy.com's bandwidth.",
                        "",
                        "Limitation: these checks CANNOT detect a server with a public IP whose HTTP",
                        "port is firewalled. If clients can't download the self-hosted pack despite",
                        "the checks passing, either open the firewall port OR set preferSelfHost: false.",
                        "",
                        "Either set selfHostExternalHost to your public hostname, OR ensure the plugin",
                        "can reach api.ipify.org / checkip.amazonaws.com to auto-detect a public IPv4.",
                        "If neither yields a routable host, self-host is skipped and the plugin uses",
                        "remote hosting.",
                        "",
                        "Ignored when selfHostForce=true (force overrides everything) or selfHostEnabled=false",
                        "(self-host disabled entirely => always remote)."),
                fileConfiguration, "preferSelfHost", true);
    }
}
