package com.magmaguy.resourcepackmanager.bungee;

import com.magmaguy.resourcepackmanager.mixer.engine.MixerLogger;
import com.magmaguy.resourcepackmanager.proxy.GeyserBinder;
import com.magmaguy.resourcepackmanager.proxy.GeyserMappingsDeployer;
import com.magmaguy.resourcepackmanager.proxy.MergedPack;
import com.magmaguy.resourcepackmanager.proxy.NetworkSync;
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

        String effectiveKey = config.networkKey();
        if (effectiveKey == null || effectiveKey.isBlank()) {
            // Auto-derive from Floodgate key.pem on the proxy
            java.nio.file.Path keyPem = getDataFolder().getParentFile().toPath()   // plugins/
                    .resolve("floodgate")
                    .resolve("key.pem");
            effectiveKey = com.magmaguy.resourcepackmanager.http.NetworkKeyResolver.deriveFromFloodgateKey(keyPem);
            if (effectiveKey != null) {
                getLogger().info("[RSPM] Network-key auto-derived from Floodgate key.pem (" + effectiveKey + "). Override via network-key in config.yml if needed.");
            }
        }
        if (effectiveKey == null || effectiveKey.isBlank()) {
            getLogger().warning("[RSPM] No network-key. Floodgate key.pem missing from plugins/floodgate/ — install Floodgate on this proxy, or set network-key explicitly in config.yml. Plugin idle.");
            return;
        }

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
        File geyserPluginDir = GeyserMappingsDeployer.detectGeyserPluginDir(proxyPluginsDir);

        // Boot-time pre-deploy of the previous run's Geyser mappings — Geyser's
        // custom-item registry is boot-frozen, so anything we generate AFTER its
        // startup waits for the next proxy restart to apply.
        File workingDir = new File(getDataFolder(), "work");
        File previousMergedMappings = new File(new File(workingDir, "merged"), "rspm_geyser_mappings.json");
        if (previousMergedMappings.isFile() && geyserPluginDir != null) {
            if (GeyserMappingsDeployer.isEmptyMappings(previousMergedMappings)) {
                logger.info("Previous Geyser mappings file exists but is empty (no items); skipping boot-time pre-deploy.");
            } else {
                GeyserMappingsDeployer.deploy(geyserPluginDir, previousMergedMappings, logger);
                logger.info("Pre-deployed previous Geyser mappings for boot-time registration.");
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
                this::onMergedPackReady);

        boolean geyserPresent = getProxy().getPluginManager().getPlugin("Geyser-BungeeCord") != null;
        if (geyserPresent) {
            this.bedrock = new GeyserBinder(logger, EventRegistrar.of(this), this::broadcastBedrockPackUnavailable);
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
        logger.info("RSPM proxy plugin started (network-key=" + effectiveKey + "). Java pack push is handled by backends; this proxy plugin is Bedrock-only.");
    }

    @Override
    public void onDisable() {
        if (sync != null) sync.stop();
        if (bedrock != null) bedrock.unregister();
    }

    /**
     * Tracks whether the "pack is now ready" broadcast has already fired this
     * proxy session — fire-once semantics, identical to Velocity's tracking.
     * Operators want to know the FIRST time a pack becomes available, not
     * every poll cycle (that would spam chat every 30s).
     */
    private volatile boolean packReadyAnnounced = false;

    private void onMergedPackReady(MergedPack pack) {
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
