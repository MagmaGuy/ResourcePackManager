package com.magmaguy.rspm.bungee;

import com.magmaguy.rspm.http.MagmaguyRspClient;
import com.magmaguy.rspm.mixer.MixerLogger;
import com.magmaguy.rspm.proxy.GeyserBinder;
import com.magmaguy.rspm.proxy.MergedPack;
import com.magmaguy.rspm.proxy.NetworkSync;
import net.md_5.bungee.api.plugin.Plugin;
import org.geysermc.geyser.api.event.EventRegistrar;

import java.io.File;

public final class RspmBungeePlugin extends Plugin {

    private BungeeProxyLogger logger;
    private RspmBungeeConfig config;
    private MagmaguyRspClient httpClient;
    private NetworkSync sync;
    private GeyserBinder bedrock;
    private ProtocolizeBinder protocolize;

    @Override
    public void onEnable() {
        this.logger = new BungeeProxyLogger(getLogger());

        try {
            this.config = RspmBungeeConfig.loadOrCreate(getDataFolder().toPath());
        } catch (Exception e) {
            getLogger().severe("Failed to load config; plugin will not start.");
            return;
        }

        if (config.networkKey() == null || config.networkKey().isBlank()) {
            getLogger().warning("[RSPM] network-key not set in plugins/ResourcePackManager/config.yml - plugin idle until you configure it.");
            getLogger().warning("[RSPM] Look at the backend RPM's startup console for the auto-generated key.");
            return;
        }

        // Soft-depend on Protocolize for Java pack push. If absent, we still
        // ship the merged pack to Bedrock clients via Geyser, but Java clients
        // won't see it until the admin installs Protocolize.
        if (getProxy().getPluginManager().getPlugin("Protocolize") == null) {
            getLogger().warning("[RSPM] Protocolize not detected. Bedrock pack delivery will still work via Geyser, but Java pack push requires Protocolize.");
            getLogger().warning("[RSPM] Install Protocolize from https://www.spigotmc.org/resources/protocolize.63778/");
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

        BungeeScheduler scheduler = new BungeeScheduler(this);

        this.sync = new NetworkSync(
                logger,
                scheduler,
                httpClient,
                mixerLogger,
                new File(getDataFolder(), "work"),
                config.networkKey(),
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

        if (getProxy().getPluginManager().getPlugin("Protocolize") != null) {
            this.protocolize = new ProtocolizeBinder(logger, config.forceResourcePack());
            getProxy().getPluginManager().registerListener(this, this.protocolize);
            logger.info("[RSPM] Protocolize binder registered - Java clients will receive the merged pack on PostLoginEvent.");
        }

        // First poll after 5s so the rest of the proxy / Geyser finishes startup; then every 30s.
        this.sync.start(5_000L, 30_000L);
        logger.info("RSPM proxy plugin started (network-key=" + config.networkKey() + ").");
    }

    @Override
    public void onDisable() {
        if (sync != null) sync.stop();
        if (bedrock != null) bedrock.unregister();
        if (httpClient != null) {
            try {
                httpClient.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void onMergedPackReady(MergedPack pack) {
        if (bedrock != null) bedrock.onMergedPackReady(pack);
        if (protocolize != null) protocolize.onMergedPackReady(pack);
        logger.info("Merged pack ready at " + pack.url() + " (sha1 " + pack.sha1Hex() + ")");
    }
}
