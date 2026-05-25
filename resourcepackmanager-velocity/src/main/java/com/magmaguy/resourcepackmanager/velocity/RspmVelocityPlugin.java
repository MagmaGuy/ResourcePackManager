package com.magmaguy.resourcepackmanager.velocity;

import com.google.inject.Inject;
import com.magmaguy.resourcepackmanager.mixer.engine.MixerLogger;
import com.magmaguy.resourcepackmanager.proxy.BackendMetadataPoller;
import com.magmaguy.resourcepackmanager.proxy.GeyserBinder;
import com.magmaguy.resourcepackmanager.proxy.MergedPack;
import com.magmaguy.resourcepackmanager.proxy.NetworkSync;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.geysermc.geyser.api.event.EventRegistrar;
import org.slf4j.Logger;

import java.nio.file.Path;

@Plugin(
        id = "resourcepackmanager",
        name = "ResourcePackManager",
        version = "1.8.0",
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
    private BackendMetadataPoller poller;

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

        String effectiveKey = config.networkKey();
        if (effectiveKey == null || effectiveKey.isBlank()) {
            // Auto-derive from Floodgate key.pem on the proxy
            java.nio.file.Path keyPem = dataDir.getParent()   // plugins/
                    .resolve("floodgate")
                    .resolve("key.pem");
            effectiveKey = com.magmaguy.resourcepackmanager.http.NetworkKeyResolver.deriveFromFloodgateKey(keyPem);
            if (effectiveKey != null) {
                slf4j.info("[RSPM] Network-key auto-derived from Floodgate key.pem ({}). Override via network-key in config.yml if needed.", effectiveKey);
            }
        }
        if (effectiveKey == null || effectiveKey.isBlank()) {
            slf4j.warn("[RSPM] No network-key. Floodgate key.pem missing from plugins/floodgate/ — install Floodgate on this proxy, or set network-key explicitly in config.yml. Plugin idle.");
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

        VelocityScheduler scheduler = new VelocityScheduler(this, proxy);

        // Backend metadata poller: hits every backend's /.rspm-pack-info.json on a
        // 60s cycle and aggregates the responses into a ManifestResult NetworkSync
        // can consume. Replaces the previous magmaguy.com-manifest stub + plugin-
        // message-cache fallback.
        this.poller = new BackendMetadataPoller(
                logger,
                scheduler,
                new VelocityBackendListProvider(proxy),
                config.backendMetadataPort(),
                effectiveKey);

        this.sync = new NetworkSync(
                logger,
                scheduler,
                poller::getLatestManifest,
                mixerLogger,
                dataDir.resolve("work").toFile(),
                effectiveKey,
                config.selfHostPort(),
                config.selfHostExternalHost(),
                this::onMergedPackReady);

        boolean geyserPresent = proxy.getPluginManager().getPlugin("geyser").isPresent();
        if (geyserPresent) {
            this.bedrock = new GeyserBinder(logger, EventRegistrar.of(this));
            this.bedrock.register();
        } else {
            logger.warn("[RSPM] Geyser-Velocity not detected. Bedrock pack delivery disabled. Install Geyser-Velocity to deliver packs to Bedrock players.");
        }

        // Poll backends first (2s grace so Velocity finishes server registration),
        // then run the mix loop with a slightly longer initial delay so the first
        // mix sees actual data.
        this.poller.start(2_000L, 60_000L);
        this.sync.start(5_000L, 30_000L);
        logger.info("RSPM proxy plugin started (network-key=" + effectiveKey + "). Java pack push is handled by backends; this proxy plugin is Bedrock-only.");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (sync != null) sync.stop();
        if (poller != null) poller.stop();
        if (bedrock != null) bedrock.unregister();
    }

    private void onMergedPackReady(MergedPack pack) {
        // GeyserBinder reads sync.current() lazily so it doesn't need an explicit handle,
        // but we still call its callback so the binder is consistent.
        if (bedrock != null) bedrock.onMergedPackReady(pack);
        logger.info("Merged pack ready at " + pack.url() + " (sha1 " + pack.sha1Hex() + ")");
    }
}
