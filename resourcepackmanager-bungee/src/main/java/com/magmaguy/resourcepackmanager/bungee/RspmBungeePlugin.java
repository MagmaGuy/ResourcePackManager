package com.magmaguy.resourcepackmanager.bungee;

import com.magmaguy.resourcepackmanager.mixer.engine.MixerLogger;
import com.magmaguy.resourcepackmanager.proxy.BackendMetadataPoller;
import com.magmaguy.resourcepackmanager.proxy.GeyserBinder;
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
    private BackendMetadataPoller poller;

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

        // Backend metadata poller: hits every backend's /.rspm-pack-info.json on a
        // 60s cycle and aggregates the responses into a ManifestResult NetworkSync
        // can consume. Replaces the previous magmaguy.com-manifest stub + plugin-
        // message-cache fallback.
        this.poller = new BackendMetadataPoller(
                logger,
                scheduler,
                new BungeeBackendListProvider(this),
                config.backendMetadataPort(),
                effectiveKey);

        this.sync = new NetworkSync(
                logger,
                scheduler,
                poller::getLatestManifest,
                mixerLogger,
                new File(getDataFolder(), "work"),
                effectiveKey,
                config.selfHostPort(),
                config.selfHostExternalHost(),
                this::onMergedPackReady);

        boolean geyserPresent = getProxy().getPluginManager().getPlugin("Geyser-BungeeCord") != null;
        if (geyserPresent) {
            this.bedrock = new GeyserBinder(logger, EventRegistrar.of(this));
            this.bedrock.register();
        } else {
            getLogger().warning("[RSPM] Geyser-BungeeCord not detected. Bedrock pack delivery disabled. Install Geyser-BungeeCord to deliver packs to Bedrock players.");
        }

        // Poll backends first (2s grace so Bungee finishes server registration),
        // then run the mix loop with a slightly longer initial delay so the first
        // mix sees actual data.
        this.poller.start(2_000L, 60_000L);
        this.sync.start(5_000L, 30_000L);
        logger.info("RSPM proxy plugin started (network-key=" + effectiveKey + "). Java pack push is handled by backends; this proxy plugin is Bedrock-only.");
    }

    @Override
    public void onDisable() {
        if (sync != null) sync.stop();
        if (poller != null) poller.stop();
        if (bedrock != null) bedrock.unregister();
    }

    private void onMergedPackReady(MergedPack pack) {
        if (bedrock != null) bedrock.onMergedPackReady(pack);
        logger.info("Merged pack ready at " + pack.url() + " (sha1 " + pack.sha1Hex() + ")");
    }
}
