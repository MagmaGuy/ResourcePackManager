package com.magmaguy.resourcepackmanager.bungee;

import com.magmaguy.resourcepackmanager.proxy.NetworkSync;
import com.magmaguy.resourcepackmanager.proxy.ProxyStatusRenderer;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Plugin;

import java.io.File;
import java.util.function.Supplier;

/**
 * {@code /rspm status} on the BungeeCord/Waterfall proxy. Same output as the
 * Velocity variant — see {@code RspmVelocityStatusCommand} for the rationale.
 *
 * <p>Implementation differs only in the platform adapter shape: BungeeCord
 * uses {@code Command} subclasses + {@code BaseComponent[]} for chat output
 * vs Velocity's {@code SimpleCommand} + Adventure {@code Component}. The
 * actual rendering is delegated to the shared {@link ProxyStatusRenderer}
 * so the two platforms never drift.</p>
 */
final class RspmBungeeStatusCommand extends Command {

    private final Plugin plugin;
    private final Supplier<NetworkSync> syncSupplier;
    private final Supplier<String> networkKeySupplier;
    private final Supplier<File> geyserPluginDirSupplier;

    RspmBungeeStatusCommand(Plugin plugin,
                            Supplier<NetworkSync> syncSupplier,
                            Supplier<String> networkKeySupplier,
                            Supplier<File> geyserPluginDirSupplier) {
        super("rspm", "resourcepackmanager.command.status");
        this.plugin = plugin;
        this.syncSupplier = syncSupplier;
        this.networkKeySupplier = networkKeySupplier;
        this.geyserPluginDirSupplier = geyserPluginDirSupplier;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("debug")) {
            // /rspm debug bedrock [on|off] — runtime toggle for the
            // [RSPM-BedrockDebug] log stream. Same semantics as the Velocity
            // command. Intentionally not persisted in config (would be too
            // easy to leave on; the logging is verbose).
            handleDebugSubcommand(sender, args);
            return;
        }
        if (args.length < 1 || !args[0].equalsIgnoreCase("status")) {
            sender.sendMessage(TextComponent.fromLegacyText("Usage: /rspm <status|debug bedrock [on|off]>"));
            return;
        }
        NetworkSync sync = syncSupplier.get();
        if (sync == null) {
            sender.sendMessage(TextComponent.fromLegacyText(
                    ChatColor.RED + "[RSPM] NetworkSync isn't running — see the proxy boot log"));
            sender.sendMessage(TextComponent.fromLegacyText(
                    ChatColor.RED + "       for the reason (typically: Floodgate key.pem missing)."));
            return;
        }
        NetworkSync.Snapshot snap = sync.snapshot();
        String version = plugin.getDescription().getVersion();
        // Bungee-side plugin presence checks. The "floodgate" name matches the
        // bungeecord-floodgate jar's plugin.yml name; "Geyser-BungeeCord" is what
        // the geyser-bungeecord build calls itself in its plugin.yml.
        boolean geyser = plugin.getProxy().getPluginManager().getPlugin("Geyser-BungeeCord") != null;
        boolean floodgate = plugin.getProxy().getPluginManager().getPlugin("floodgate") != null;
        File geyserDir = geyserPluginDirSupplier.get();

        ProxyStatusRenderer renderer = new ProxyStatusRenderer(
                version, networkKeySupplier.get(), snap, geyser, floodgate, geyserDir);
        renderer.render(line -> sendLegacyLine(sender, line));
    }

    /**
     * {@code /rspm debug bedrock [on|off]} — Bungee variant of the Velocity
     * subcommand. Toggles {@link com.magmaguy.resourcepackmanager.proxy.BedrockDeliveryDebugLog}
     * at runtime. State resets on proxy restart.
     */
    private static void handleDebugSubcommand(CommandSender sender, String[] args) {
        // args[0] = "debug"; expect args[1] = "bedrock"; args[2] = optional on/off
        if (args.length < 2 || !"bedrock".equalsIgnoreCase(args[1])) {
            sender.sendMessage(TextComponent.fromLegacyText(
                    "Usage: /rspm debug bedrock [on|off] — currently only the 'bedrock' subsystem is supported."));
            return;
        }
        if (args.length < 3) {
            boolean cur = com.magmaguy.resourcepackmanager.proxy
                    .BedrockDeliveryDebugLog.isEnabled();
            sender.sendMessage(TextComponent.fromLegacyText(
                    "[RSPM] Bedrock delivery debug logging is currently "
                            + (cur ? "ON" : "OFF")
                            + ". Use /rspm debug bedrock on|off to change."));
            return;
        }
        boolean target;
        switch (args[2].toLowerCase()) {
            case "on", "true", "enable", "enabled" -> target = true;
            case "off", "false", "disable", "disabled" -> target = false;
            default -> {
                sender.sendMessage(TextComponent.fromLegacyText(
                        "Unknown state '" + args[2] + "'. Expected 'on' or 'off'."));
                return;
            }
        }
        com.magmaguy.resourcepackmanager.proxy.BedrockDeliveryDebugLog.setEnabled(target);
        sender.sendMessage(TextComponent.fromLegacyText(
                "[RSPM] Bedrock delivery debug logging is now "
                        + (target ? "ON" : "OFF")
                        + ". Log lines prefixed with [RSPM-BedrockDebug]. "
                        + (target
                            ? "Reproduce the issue then turn this OFF."
                            : "")));
    }

    /**
     * Map MagmaCore's ampersand color codes to Bungee's legacy section-symbol
     * format via {@link ChatColor#translateAlternateColorCodes(char, String)}.
     * Empty strings emit a blank line for visual spacing — Bungee renders an
     * empty TextComponent as a single newline in chat, matching the Velocity
     * behavior.
     */
    private static void sendLegacyLine(CommandSender sender, String legacyWithAmpersand) {
        if (legacyWithAmpersand.isEmpty()) {
            sender.sendMessage(TextComponent.fromLegacyText(" "));
            return;
        }
        String translated = ChatColor.translateAlternateColorCodes('&', legacyWithAmpersand);
        BaseComponent[] components = TextComponent.fromLegacyText(translated);
        sender.sendMessage(components);
    }
}
