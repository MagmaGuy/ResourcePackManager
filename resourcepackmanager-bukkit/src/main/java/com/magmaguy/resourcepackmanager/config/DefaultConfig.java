package com.magmaguy.resourcepackmanager.config;

import com.magmaguy.magmacore.config.ConfigurationEngine;
import com.magmaguy.magmacore.config.ConfigurationFile;
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
    private static String networkKey = "";
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


    public DefaultConfig() {
        super("config.yml");
    }

    @Override
    public void initializeValues() {
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

        networkKey = ConfigurationEngine.setString(
                List.of(
                        "Pin a network key explicitly. Leave empty to auto-generate on first boot (recommended).",
                        "Multi-backend networks: paste the SAME network key into every backend's RPM config",
                        "AND into the proxy plugin's config to link them. The key is logged prominently",
                        "on every boot when network mode is active."),
                fileConfiguration, "networkKey", "");

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
                        "-1 (default) = auto-derive: HTTP port = Minecraft server port + networkHttpOffset.",
                        "  This makes single-host networks (multiple backends on localhost) auto-stagger:",
                        "  each backend already has a unique MC port, so each gets a unique HTTP port,",
                        "  with zero admin configuration.",
                        "Set to any positive value to force an explicit port (legacy behaviour, default was 25567)."),
                fileConfiguration, "selfHostPort", -1);

        networkHttpOffset = ConfigurationEngine.setInt(
                List.of(
                        "Offset added to the Minecraft server port to derive the HTTP port when selfHostPort = -1.",
                        "Default 100 => MC 25565 -> HTTP 25665, MC 25671 -> HTTP 25771, etc.",
                        "Must match the proxy plugin's network-http-offset config. Admins rarely need to change",
                        "this — bump it only if 100 happens to collide with something already on the host."),
                fileConfiguration, "networkHttpOffset", 100);

        selfHostExternalHost = ConfigurationEngine.setString(
                List.of(
                        "Public hostname or IP that clients use to reach your self-host server.",
                        "Leave empty to auto-detect via Bukkit.getIp() / InetAddress (best-effort).",
                        "If clients connect from outside your LAN, set this to your public hostname (e.g. play.example.com)."),
                fileConfiguration, "selfHostExternalHost", "");

        selfHostForce = ConfigurationEngine.setBoolean(
                List.of(
                        "Skip the magmaguy.com upload attempt entirely and self-host directly.",
                        "Mainly for testing the self-host path. In production, leave this false so auto-upload is tried first.",
                        "When true, this overrides selfHostEnabled (self-hosting always happens)."),
                fileConfiguration, "selfHostForce", false);
    }
}
