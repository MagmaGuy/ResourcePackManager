package com.magmaguy.resourcepackmanager.velocity;

import com.google.inject.Inject;
import com.magmaguy.resourcepackmanager.mixer.engine.MixerLogger;
import com.magmaguy.resourcepackmanager.proxy.GeyserBinder;
import com.magmaguy.resourcepackmanager.proxy.GeyserBridgeExtensionInstaller;
import com.magmaguy.resourcepackmanager.proxy.GeyserMappingsDeployer;
import com.magmaguy.resourcepackmanager.proxy.MergedPack;
import com.magmaguy.resourcepackmanager.proxy.NetworkSync;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.geysermc.geyser.api.event.EventRegistrar;
import org.slf4j.Logger;

import java.io.File;
import java.nio.file.Path;

@Plugin(
        id = "resourcepackmanager",
        name = "ResourcePackManager",
        version = "2.2.2",
        description = "Network-side companion to ResourcePackManager. Delivers the merged pack to Bedrock clients via Geyser on this proxy.",
        authors = {"MagmaGuy"},
        dependencies = {
                @Dependency(id = "geyser", optional = true)
        }
)
public final class RspmVelocityPlugin {

    private final ProxyServer proxy;
    private final Logger slf4j;
    private final Path dataDir;

    private VelocityProxyLogger logger;
    private RspmVelocityConfig config;
    private NetworkSync sync;
    private GeyserBinder bedrock;

    @Inject
    public RspmVelocityPlugin(ProxyServer proxy, Logger slf4j, @DataDirectory Path dataDir) {
        this.proxy = proxy;
        this.slf4j = slf4j;
        this.dataDir = dataDir;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        this.logger = new VelocityProxyLogger(slf4j);
        try {
            this.config = RspmVelocityConfig.loadOrCreate(dataDir);
        } catch (Exception e) {
            slf4j.error("Failed to load config; plugin will not start.", e);
            return;
        }

        // Network key is derived SOLELY from plugins/floodgate/key.pem on this proxy.
        // There is no config-pasted override path — that was retired pre-release after
        // typo'd pastes silently broke the proxy↔backend link. Floodgate already
        // requires this file to be the same on every backend AND on the proxy for
        // Bedrock players to authenticate, so the derived value matches every
        // backend's automatically. The only setup step is: install Floodgate.
        Path keyPem = dataDir.getParent()   // plugins/
                .resolve("floodgate")
                .resolve("key.pem");
        String effectiveKey = com.magmaguy.resourcepackmanager.http.NetworkKeyResolver
                .deriveFromFloodgateKey(keyPem);
        if (effectiveKey == null || effectiveKey.isBlank()) {
            slf4j.warn("[RSPM] Floodgate key.pem missing from plugins/floodgate/key.pem on this proxy.");
            slf4j.warn("[RSPM] RSPM cannot link to any backend without it. Install Floodgate on this");
            slf4j.warn("[RSPM] proxy (it's required for Bedrock players to connect anyway), then");
            slf4j.warn("[RSPM] restart. Plugin idle.");
            return;
        }
        slf4j.info("[RSPM] Network-key auto-derived from Floodgate key.pem ✓");

        MixerLogger mixerLogger = new MixerLogger() {
            @Override
            public void info(String m) {
                logger.info("[mixer] " + m);
            }

            @Override
            public void warn(String m) {
                logger.warn("[mixer] " + m);
            }

            @Override
            public void collision(String m) {
                /* swallow on proxy; we don't write collision logs here */
            }
        };

        VelocityScheduler scheduler = new VelocityScheduler(this, proxy);

        // Auto-detect the proxy's Geyser plugin folder (Geyser-Velocity in the
        // normal case). NetworkSync deploys merged mappings here after each
        // merge; we also pre-deploy the previous run's mappings below.
        File proxyPluginsDir = dataDir.getParent().toFile();
        File geyserPluginDir = GeyserMappingsDeployer.detectGeyserPluginDir(proxyPluginsDir);
        GeyserBridgeExtensionInstaller.install(geyserPluginDir, logger);

        // Boot-time pre-deploy of previous run's Geyser mappings. Geyser's custom-item
        // registry is boot-frozen; if we wait until after the first merge it's already
        // too late. Pre-deploying the previous run's file lets Geyser pick up the
        // existing mappings at proxy startup so Bedrock players hit working items
        // immediately (the just-generated mappings still need a restart, but the
        // PREVIOUS run's are already deployed).
        File workingDir = dataDir.resolve("work").toFile();
        File previousMergedMappings = new File(new File(workingDir, "merged"), "rspm_geyser_mappings.json");
        if (previousMergedMappings.isFile() && geyserPluginDir != null) {
            if (GeyserMappingsDeployer.isEmptyMappings(previousMergedMappings)) {
                logger.info("Previous Geyser mappings file exists but is empty (no items); skipping boot-time pre-deploy.");
            } else {
                // Silent pre-deploy — boot-time internal plumbing, no operator value.
                GeyserMappingsDeployer.deploy(geyserPluginDir, previousMergedMappings, logger);
            }
        }

        this.sync = new NetworkSync(
                logger,
                scheduler,
                new VelocityBackendListProvider(proxy),
                workingDir,
                config.networkHttpOffset(),
                mixerLogger,
                geyserPluginDir,
                effectiveKey,
                this::onMergedPackReady);

        boolean geyserPresent = proxy.getPluginManager().getPlugin("geyser").isPresent();
        if (geyserPresent) {
            this.bedrock = new GeyserBinder(logger, EventRegistrar.of(this), this::broadcastBedrockPackUnavailable);
            this.bedrock.register();
        } else {
            logger.warn("[RSPM] Geyser-Velocity not detected. Bedrock pack delivery disabled. Install Geyser-Velocity to deliver packs to Bedrock players.");
        }

        // First poll after 2s so Velocity has time to finish server registration
        // (any race shows up as zero backends in this poll → next cycle picks them
        // up). Thereafter every 5s during the boot window, which combined with the
        // 1-cycle stability gate gets the first merge published ~7s after proxy
        // boot if backends are already up. Polls are HTTP HEAD-ish with
        // If-Modified-Since so steady-state cost is ~0 bytes per cycle per backend
        // when nothing changed — cheap enough to leave fast.
        this.sync.start(2_000L, 5_000L);

        // Register /rspm status. The status command captures live NetworkSync state
        // (backend list, fetch outcomes, merge state) — operators run this when
        // Bedrock players aren't getting the pack and the proxy log has been quiet,
        // which is the exact failure mode the silent-IOException path in
        // fetchIfChanged created in production before the diagnostic snapshot existed.
        // Final-effective for the lambda — local copy of effectiveKey so the supplier
        // captures the boot-time resolved value.
        final String resolvedKey = effectiveKey;
        final File detectedGeyserDir = geyserPluginDir;
        com.velocitypowered.api.command.CommandManager commandManager = proxy.getCommandManager();
        com.velocitypowered.api.command.CommandMeta meta = commandManager.metaBuilder("rspm").build();
        commandManager.register(meta,
                new com.velocitypowered.api.command.SimpleCommand() {
                    private final RspmVelocityStatusCommand status = new RspmVelocityStatusCommand(
                            proxy.getPluginManager().getPlugin("resourcepackmanager").orElseThrow(),
                            proxy,
                            () -> RspmVelocityPlugin.this.sync,
                            () -> resolvedKey,
                            () -> detectedGeyserDir);

                    @Override
                    public void execute(Invocation invocation) {
                        String[] args = invocation.arguments();
                        if (args.length >= 1 && args[0].equalsIgnoreCase("status")) {
                            status.execute(invocation);
                            return;
                        }
                        if (args.length >= 1 && args[0].equalsIgnoreCase("debug")) {
                            handleDebugSubcommand(invocation, args);
                            return;
                        }
                        invocation.source().sendMessage(net.kyori.adventure.text.Component
                                .text("Usage: /rspm <status|debug bedrock [on|off]>"));
                    }

                    /**
                     * {@code /rspm debug bedrock [on|off]} — runtime toggle for the
                     * {@code [RSPM-BedrockDebug]} log stream emitted by
                     * {@link com.magmaguy.resourcepackmanager.proxy.GeyserBinder}.
                     * Intentionally not stored in config (would be too easy to leave
                     * on; the logging is verbose). State resets on proxy restart.
                     */
                    private void handleDebugSubcommand(Invocation invocation, String[] args) {
                        // args[0] = "debug"; expect args[1] = "bedrock"; args[2] = optional on/off
                        if (args.length < 2 || !"bedrock".equalsIgnoreCase(args[1])) {
                            invocation.source().sendMessage(net.kyori.adventure.text.Component.text(
                                    "Usage: /rspm debug bedrock [on|off] — currently only the 'bedrock' subsystem is supported."));
                            return;
                        }
                        if (args.length < 3) {
                            boolean cur = com.magmaguy.resourcepackmanager.proxy
                                    .BedrockDeliveryDebugLog.isEnabled();
                            invocation.source().sendMessage(net.kyori.adventure.text.Component.text(
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
                                invocation.source().sendMessage(net.kyori.adventure.text.Component.text(
                                        "Unknown state '" + args[2] + "'. Expected 'on' or 'off'."));
                                return;
                            }
                        }
                        com.magmaguy.resourcepackmanager.proxy.BedrockDeliveryDebugLog.setEnabled(target);
                        invocation.source().sendMessage(net.kyori.adventure.text.Component.text(
                                "[RSPM] Bedrock delivery debug logging is now "
                                        + (target ? "ON" : "OFF")
                                        + ". Log lines prefixed with [RSPM-BedrockDebug]. "
                                        + (target
                                            ? "Reproduce the issue then turn this OFF."
                                            : "")));
                    }

                    @Override
                    public boolean hasPermission(Invocation invocation) {
                        return status.hasPermission(invocation);
                    }
                });

        // Startup is intentionally silent on the routine "we booted" line —
        // /rspm status surfaces the same information on demand and the
        // Network-key auto-derived ✓ line above already confirms the
        // critical handshake is good.
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (sync != null) sync.stop();
        if (bedrock != null) bedrock.unregister();
    }

    /**
     * Tracks whether the "pack is now ready" broadcast has already fired this
     * proxy session. Operators want to know the FIRST time a pack becomes
     * available (so they know "any Bedrock player connecting from here will
     * receive custom models"), not every poll cycle — that would be 1 spam
     * message every 30 seconds.
     */
    private volatile boolean packReadyAnnounced = false;

    private void onMergedPackReady(MergedPack pack) {
        if (bedrock != null) bedrock.onMergedPackReady(pack);
        logger.info("Merged pack ready at " + pack.packFile().getAbsolutePath()
                + " (sha1 " + pack.sha1Hex() + ")");
        if (!packReadyAnnounced) {
            packReadyAnnounced = true;
            announcePackReady(pack);
        }
    }

    /**
     * Called by {@link GeyserBinder} whenever a Bedrock session loads without
     * a usable RSPM pack. Broadcasts a chat warning to all online Java players
     * on this proxy so in-game admins see "this Bedrock player isn't seeing
     * models" without having to scrape the proxy log. The Bedrock player
     * themselves also gets a modal popup and the proxy console gets a banner
     * — this is the third surface, aimed at Java-side admins.
     */
    private void broadcastBedrockPackUnavailable(String bedrockPlayerName, String reason) {
        Component msg = Component.text()
                .append(Component.text("⚠ ", NamedTextColor.RED, TextDecoration.BOLD))
                .append(Component.text("[RSPM] ", NamedTextColor.YELLOW, TextDecoration.BOLD))
                .append(Component.text("Bedrock player ", NamedTextColor.WHITE))
                .append(Component.text(bedrockPlayerName, NamedTextColor.AQUA, TextDecoration.BOLD))
                .append(Component.text(" connected before the resource pack was ready — they're seeing plain armor stands instead of custom models. ", NamedTextColor.WHITE))
                .append(Component.text("Tell them to disconnect and reconnect; the pack will load on their next session.", NamedTextColor.YELLOW))
                .append(Component.text(" (Cause: " + reason + ")", NamedTextColor.GRAY))
                .build();
        proxy.getConsoleCommandSource().sendMessage(msg);
        proxy.getAllPlayers().forEach(p -> p.sendMessage(msg));
    }

    /**
     * Fire-once broadcast to console + all online players the FIRST time a
     * pack becomes available after proxy boot. Bookends the
     * "pack-not-ready" modal {@link GeyserBinder} fires at Bedrock-session-
     * load time: the modal tells a too-early-joining Bedrock player to
     * reconnect; this broadcast tells the operator (and anyone already in
     * chat) that the moment to reconnect has arrived.
     */
    private void announcePackReady(MergedPack pack) {
        Component msg = Component.text()
                .append(Component.text("✔ ", NamedTextColor.GREEN, TextDecoration.BOLD))
                .append(Component.text("[RSPM] ", NamedTextColor.YELLOW, TextDecoration.BOLD))
                .append(Component.text("Network resource pack is now ready ", NamedTextColor.WHITE))
                .append(Component.text("(" + pack.packFile().length() / 1024 + " KB, sha1 " + pack.sha1Hex().substring(0, 8) + ")", NamedTextColor.GRAY))
                .append(Component.text(". Bedrock players who connected before this should disconnect and reconnect to receive custom models.", NamedTextColor.WHITE))
                .build();
        proxy.getConsoleCommandSource().sendMessage(msg);
        proxy.getAllPlayers().forEach(p -> p.sendMessage(msg));
    }
}
