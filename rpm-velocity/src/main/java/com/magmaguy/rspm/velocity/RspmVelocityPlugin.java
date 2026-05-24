package com.magmaguy.rspm.velocity;

import com.google.inject.Inject;
import com.magmaguy.rspm.http.MagmaguyRspClient;
import com.magmaguy.rspm.mixer.MixerLogger;
import com.magmaguy.rspm.proxy.GeyserBinder;
import com.magmaguy.rspm.proxy.MergedPack;
import com.magmaguy.rspm.proxy.NetworkSync;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.player.ResourcePackInfo;
import org.geysermc.geyser.api.event.EventRegistrar;
import org.slf4j.Logger;

import java.nio.file.Path;

@Plugin(
        id = "resourcepackmanager",
        name = "ResourcePackManager",
        version = "1.8.0",
        description = "Network-side companion to ResourcePackManager. Delivers the merged pack to Java + Bedrock clients via this proxy.",
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
    private MagmaguyRspClient httpClient;
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

        if (config.networkKey() == null || config.networkKey().isBlank()) {
            slf4j.warn("[RSPM] rsp network-key not set in plugins/ResourcePackManager/config.yml - plugin idle until you configure it.");
            slf4j.warn("[RSPM] Look at the backend RPM's startup console for the auto-generated key.");
            return;
        }

        this.httpClient = new MagmaguyRspClient(
                java.util.logging.Logger.getLogger("ResourcePackManager"),
                30, 300);

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

        this.sync = new NetworkSync(
                logger,
                scheduler,
                httpClient,
                mixerLogger,
                dataDir.resolve("work").toFile(),
                config.networkKey(),
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

        // First poll after 5s so the rest of the proxy / Geyser finishes startup; then every 30s.
        this.sync.start(5_000L, 30_000L);
        logger.info("RSPM proxy plugin started (network-key=" + config.networkKey() + ").");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (sync != null) sync.stop();
        if (bedrock != null) bedrock.unregister();
        if (httpClient != null) {
            try {
                httpClient.close();
            } catch (Exception ignored) {
            }
        }
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        if (sync == null) return;
        MergedPack pack = sync.current();
        if (pack == null) return;
        Player player = event.getPlayer();
        try {
            ResourcePackInfo info = proxy.createResourcePackBuilder(pack.url())
                    .setHash(pack.sha1Bytes())
                    .setId(pack.packUuid())
                    .setShouldForce(config.forceResourcePack())
                    .build();
            player.sendResourcePackOffer(info);
        } catch (Throwable t) {
            logger.warn("Failed to offer resource pack to " + player.getUsername(), t);
        }
    }

    private void onMergedPackReady(MergedPack pack) {
        // GeyserBinder reads sync.current() lazily so it doesn't need an explicit handle,
        // but we still call its callback so the binder is consistent.
        if (bedrock != null) bedrock.onMergedPackReady(pack);
        logger.info("Merged pack ready at " + pack.url() + " (sha1 " + pack.sha1Hex() + ")");
    }
}
