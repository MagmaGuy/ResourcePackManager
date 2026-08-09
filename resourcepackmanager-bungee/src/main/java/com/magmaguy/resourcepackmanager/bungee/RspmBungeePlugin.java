package com.magmaguy.resourcepackmanager.bungee;

import com.magmaguy.resourcepackmanager.mixer.engine.MixerLogger;
import com.magmaguy.resourcepackmanager.proxy.GeyserBinder;
import com.magmaguy.resourcepackmanager.proxy.GeyserBridgeExtensionInstaller;
import com.magmaguy.resourcepackmanager.proxy.GeyserMappingsDeployer;
import com.magmaguy.resourcepackmanager.proxy.MergedPack;
import com.magmaguy.resourcepackmanager.proxy.MergedOutputPublication;
import com.magmaguy.resourcepackmanager.proxy.NetworkSync;
import com.magmaguy.resourcepackmanager.proxy.ProxyPluginUpdateCoordinator;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.plugin.Plugin;
import org.geysermc.geyser.api.event.EventRegistrar;

import java.io.File;

public final class RspmBungeePlugin extends Plugin {

    private BungeeProxyLogger logger;
    private RspmBungeeConfig config;
    private NetworkSync sync;
    private ProxyPluginUpdateCoordinator pluginUpdateCoordinator;
    private GeyserBinder bedrock;

    @Override
    public void onEnable() {
        this.logger = new BungeeProxyLogger(getLogger());

        try {
            this.config = RspmBungeeConfig.loadOrCreate(getDataFolder().toPath());
        } catch (Exception e) {
            getLogger().severe("Failed to load config; plugin will not start.");
            return;
        }

        // Network key is derived SOLELY from plugins/floodgate/key.pem on this proxy.
        // There is no config-pasted override path — that was retired pre-release after
        // typo'd pastes silently broke the proxy↔backend link. Floodgate already
        // requires this file to be the same on every backend AND on the proxy for
        // Bedrock players to authenticate, so the derived value matches every
        // backend's automatically. The only setup step is: install Floodgate.
        java.nio.file.Path keyPem = getDataFolder().getParentFile().toPath()   // plugins/
                .resolve("floodgate")
                .resolve("key.pem");
        String effectiveKey = com.magmaguy.resourcepackmanager.http.NetworkKeyResolver
                .deriveFromFloodgateKey(keyPem);
        if (effectiveKey == null || effectiveKey.isBlank()) {
            getLogger().warning("[RSPM] Floodgate key.pem missing from plugins/floodgate/key.pem on this proxy.");
            getLogger().warning("[RSPM] RSPM cannot link to any backend without it. Install Floodgate on this");
            getLogger().warning("[RSPM] proxy (it's required for Bedrock players to connect anyway), then");
            getLogger().warning("[RSPM] restart. Plugin idle.");
            return;
        }
        getLogger().info("[RSPM] Network-key auto-derived from Floodgate key.pem ✓");

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

        BungeeScheduler scheduler = new BungeeScheduler(this);

        // Auto-detect the proxy's Geyser plugin folder (Geyser-BungeeCord on
        // BungeeCord/Waterfall). NetworkSync deploys merged mappings here after
        // each merge; we also pre-deploy the previous run's mappings below.
        File proxyPluginsDir = getDataFolder().getParentFile();
        File geyserPluginDir = GeyserMappingsDeployer.detectGeyserPluginDir(
                proxyPluginsDir, "Geyser-BungeeCord");
        GeyserBridgeExtensionInstaller.install(geyserPluginDir, logger);

        // Boot-time pre-deploy of the previous run's Geyser mappings — Geyser's
        // custom-item registry is boot-frozen, so anything we generate AFTER its
        // startup waits for the next proxy restart to apply.
        File workingDir = new File(getDataFolder(), "work");
        try {
            this.pluginUpdateCoordinator = ProxyPluginUpdateCoordinator.production(
                    workingDir.toPath(),
                    geyserPluginDir == null ? null : geyserPluginDir.toPath(),
                    logger,
                    RspmBungeePlugin.class);
        } catch (Exception exception) {
            logger.warn("Could not initialize proxy plugin update delivery. "
                    + "Pack synchronization will continue without proxy updates.", exception);
        }
        File mergedDir = new File(workingDir, "merged");
        MergedOutputPublication.Snapshot previousPublication =
                MergedOutputPublication.current(mergedDir);
        if (geyserPluginDir != null) {
            try {
                if (previousPublication != null
                        && previousPublication.hasMappings()
                        && !GeyserMappingsDeployer.isEmptyMappings(
                        previousPublication.mappings())) {
                    GeyserMappingsDeployer.deploy(
                            geyserPluginDir, previousPublication.mappings(), logger);
                } else {
                    GeyserMappingsDeployer.remove(
                            geyserPluginDir,
                            MergedOutputPublication.MAPPINGS_NAME,
                            logger);
                }
            } catch (java.io.IOException cleanupFailure) {
                logger.warn("Could not reconcile boot-time Geyser mappings authority: "
                        + cleanupFailure.getMessage());
            }
        }

        this.sync = new NetworkSync(
                logger,
                scheduler,
                new BungeeBackendListProvider(this),
                workingDir,
                config.networkHttpOffset(),
                mixerLogger,
                geyserPluginDir,
                effectiveKey,
                this::onMergedPackReady,
                pluginUpdateCoordinator == null ? null : pluginUpdateCoordinator::accept);

        boolean geyserPresent = getProxy().getPluginManager().getPlugin("Geyser-BungeeCord") != null;
        if (geyserPresent) {
            this.bedrock = new GeyserBinder(logger, EventRegistrar.of(this), this::broadcastBedrockPackUnavailable);
            MergedPack preloadedPack = this.sync.current();
            if (preloadedPack != null) {
                this.bedrock.onMergedPackReady(preloadedPack);
            }
            this.bedrock.register();
        } else {
            getLogger().warning("[RSPM] Geyser-BungeeCord not detected. Bedrock pack delivery disabled. Install Geyser-BungeeCord to deliver packs to Bedrock players.");
        }

        // First poll after 2s so Bungee has time to finish server registration
        // (any race shows up as zero backends in this poll → next cycle picks them
        // up). Thereafter every 5s, which combined with the 1-cycle stability
        // gate in NetworkSync gets the first merge published ~7s after proxy boot
        // if backends are already up. Polls use If-Modified-Since so steady-state
        // cost is ~0 bytes per cycle per backend when nothing changed.
        this.sync.start(2_000L, 5_000L);

        // /rspm status — Bungee variant. See RspmBungeeStatusCommand class javadoc
        // for the operator use case; symmetric with the Velocity command.
        final String resolvedKey = effectiveKey;
        final java.io.File detectedGeyserDir = geyserPluginDir;
        getProxy().getPluginManager().registerCommand(this,
                new RspmBungeeStatusCommand(
                        this,
                        () -> RspmBungeePlugin.this.sync,
                        () -> resolvedKey,
                        () -> detectedGeyserDir));

        // See Velocity entry — silent on the routine "we booted" line; the
        // Network-key auto-derived ✓ line above is the only critical boot signal.
    }

    @Override
    public void onDisable() {
        if (sync != null) sync.stop();
        if (pluginUpdateCoordinator != null) {
            try {
                pluginUpdateCoordinator.applyPendingAtShutdown();
            } catch (Exception exception) {
                logger.warn("Unexpected failure while applying the pending proxy update. "
                        + "The verified update remains available for the prelaunch applier.", exception);
            }
        }
        if (bedrock != null) bedrock.unregister();
    }

    /**
     * Tracks whether the "pack is now ready" broadcast has already fired this
     * proxy session — fire-once semantics, identical to Velocity's tracking.
     * Operators want to know the FIRST time a pack becomes available, not
     * every poll cycle (5 s) — that would spam chat.
     */
    private volatile boolean packReadyAnnounced = false;

    private void onMergedPackReady(MergedPack pack) {
        if (pack == null) {
            if (bedrock != null) bedrock.onMergedPackReady(null);
            logger.info("Merged pack cleared; no Bedrock pack is currently published on this proxy.");
            packReadyAnnounced = false;
            return;
        }
        if (bedrock != null) bedrock.onMergedPackReady(pack);
        logger.info("Merged pack ready at " + pack.packFile().getAbsolutePath() + " (sha1 " + pack.sha1Hex() + ")");
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
        BaseComponent[] msg = new ComponentBuilder("⚠ ").color(ChatColor.RED).bold(true)
                .append("[RSPM] ").color(ChatColor.YELLOW).bold(true)
                .append("Bedrock player ").color(ChatColor.WHITE).bold(false)
                .append(bedrockPlayerName).color(ChatColor.AQUA).bold(true)
                .append(" connected before the resource pack was ready — they're seeing plain armor stands instead of custom models. ").color(ChatColor.WHITE).bold(false)
                .append("Tell them to disconnect and reconnect; the pack will load on their next session.").color(ChatColor.YELLOW)
                .append(" (Cause: " + reason + ")").color(ChatColor.GRAY)
                .create();
        getProxy().getConsole().sendMessage(msg);
        getProxy().getPlayers().forEach(p -> p.sendMessage(msg));
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
        BaseComponent[] msg = new ComponentBuilder("✔ ").color(ChatColor.GREEN).bold(true)
                .append("[RSPM] ").color(ChatColor.YELLOW).bold(true)
                .append("Network resource pack is now ready ").color(ChatColor.WHITE).bold(false)
                .append("(" + pack.packFile().length() / 1024 + " KB, sha1 " + pack.sha1Hex().substring(0, 8) + ")").color(ChatColor.GRAY)
                .append(". Bedrock players who connected before this should disconnect and reconnect to receive custom models.").color(ChatColor.WHITE)
                .create();
        getProxy().getConsole().sendMessage(msg);
        getProxy().getPlayers().forEach(p -> p.sendMessage(msg));
    }
}
