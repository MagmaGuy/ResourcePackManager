package com.magmaguy.resourcepackmanager.bungee;

import com.magmaguy.resourcepackmanager.mixer.engine.MixerLogger;
import com.magmaguy.resourcepackmanager.proxy.GeyserBinder;
import com.magmaguy.resourcepackmanager.proxy.GeyserMappingsDeployer;
import com.magmaguy.resourcepackmanager.proxy.MergedPack;
import com.magmaguy.resourcepackmanager.proxy.NetworkSync;
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
            this.bedrock = new GeyserBinder(logger, EventRegistrar.of(this));
            this.bedrock.register();
        } else {
            getLogger().warning("[RSPM] Geyser-BungeeCord not detected. Bedrock pack delivery disabled. Install Geyser-BungeeCord to deliver packs to Bedrock players.");
        }

        // First poll after 5s so Bungee finishes server registration; thereafter
        // every 30s. Stability gate inside NetworkSync requires two consecutive
        // identical hash cycles before triggering a merge.
        this.sync.start(5_000L, 30_000L);
        logger.info("RSPM proxy plugin started (network-key=" + effectiveKey + "). Java pack push is handled by backends; this proxy plugin is Bedrock-only.");
    }

    @Override
    public void onDisable() {
        if (sync != null) sync.stop();
        if (bedrock != null) bedrock.unregister();
    }

    private void onMergedPackReady(MergedPack pack) {
        if (bedrock != null) bedrock.onMergedPackReady(pack);
        logger.info("Merged pack ready at " + pack.packFile().getAbsolutePath() + " (sha1 " + pack.sha1Hex() + ")");
    }
}
