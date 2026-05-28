package com.magmaguy.resourcepackmanager.velocity;

import com.magmaguy.resourcepackmanager.proxy.NetworkSync;
import com.magmaguy.resourcepackmanager.proxy.ProxyStatusRenderer;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.io.File;
import java.util.function.Supplier;

/**
 * {@code /rspm status} on the Velocity proxy.
 *
 * <p>Surfaces the diagnostic info operators need to debug "Bedrock doesn't see
 * the pack" — what backends NetworkSync is polling, what each poll returned,
 * whether the merged pack is on disk, whether Geyser is loaded, and what to
 * fix when something's broken. The same information is in the log if you
 * scroll far enough, but the log is async + interleaved with every other
 * plugin's output; a command gives operators an at-a-glance snapshot.</p>
 *
 * <p><b>Permission:</b> {@code resourcepackmanager.command.status} — defaults
 * to allowed for everyone because the output reveals no secrets (network key
 * is masked, no auth tokens, just topology + reachability). Operators on
 * restricted servers can disable via LuckPerms or Velocity's own ACL.</p>
 */
final class RspmVelocityStatusCommand implements SimpleCommand {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacyAmpersand();

    private final PluginContainer plugin;
    private final ProxyServer proxy;
    private final Supplier<NetworkSync> syncSupplier;
    private final Supplier<String> networkKeySupplier;
    private final Supplier<File> geyserPluginDirSupplier;

    /**
     * @param plugin                  this Velocity plugin's container (for version)
     * @param proxy                   the Velocity proxy server (for plugin detection)
     * @param syncSupplier            yields the live NetworkSync instance, or null if init hasn't completed yet
     * @param networkKeySupplier      yields the auto-derived network-key (Floodgate fingerprint)
     * @param geyserPluginDirSupplier yields the detected Geyser-Velocity plugin folder, or null
     */
    RspmVelocityStatusCommand(PluginContainer plugin,
                              ProxyServer proxy,
                              Supplier<NetworkSync> syncSupplier,
                              Supplier<String> networkKeySupplier,
                              Supplier<File> geyserPluginDirSupplier) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.syncSupplier = syncSupplier;
        this.networkKeySupplier = networkKeySupplier;
        this.geyserPluginDirSupplier = geyserPluginDirSupplier;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        NetworkSync sync = syncSupplier.get();
        if (sync == null) {
            // Plugin hasn't finished initializing — usually means Floodgate key.pem
            // is missing so NetworkSync never started. Tell the operator instead of
            // silently doing nothing.
            send(source, "&c[RSPM] NetworkSync isn't running — see the proxy boot log");
            send(source, "&c       for the reason (typically: Floodgate key.pem missing).");
            return;
        }
        NetworkSync.Snapshot snap = sync.snapshot();
        String version = plugin.getDescription().getVersion().orElse("(unknown)");
        boolean geyser = proxy.getPluginManager().getPlugin("geyser").isPresent();
        boolean floodgate = proxy.getPluginManager().getPlugin("floodgate").isPresent();
        File geyserDir = geyserPluginDirSupplier.get();

        ProxyStatusRenderer renderer = new ProxyStatusRenderer(
                version, networkKeySupplier.get(), snap, geyser, floodgate, geyserDir);
        renderer.render(line -> send(source, line));
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        // Liberal default — the renderer reveals no secrets. Tightening is a
        // server policy decision, not a plugin one.
        return invocation.source().hasPermission("resourcepackmanager.command.status")
                || invocation.source().hasPermission("resourcepackmanager.*");
    }

    private static void send(CommandSource source, String legacy) {
        // Convert legacy ampersand color codes (used uniformly across backend
        // and proxy status output) into Adventure Components. Empty strings
        // become blank-line components for visual spacing.
        Component component = legacy.isEmpty()
                ? Component.empty()
                : LEGACY.deserialize(legacy);
        source.sendMessage(component);
    }
}
