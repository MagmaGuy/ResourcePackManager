package com.magmaguy.resourcepackmanager.velocity;

import com.google.inject.Inject;
import com.magmaguy.resourcepackmanager.http.MagmaguyRspClient;
import com.magmaguy.resourcepackmanager.mixer.engine.MixerLogger;
import com.magmaguy.resourcepackmanager.proxy.GeyserBinder;
import com.magmaguy.resourcepackmanager.proxy.MergedPack;
import com.magmaguy.resourcepackmanager.proxy.NetworkSync;
import com.magmaguy.resourcepackmanager.proxy.PackAdvertCache;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelMessageSource;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.geysermc.geyser.api.event.EventRegistrar;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
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

    /**
     * Plugin-messaging channel backends use to advertise their pack URL+sha1 to the
     * proxy. Two-segment lowercase channel ID — valid for modern Minecraft. Mirrors
     * {@code PackAdvertiser.CHANNEL} on the backend.
     */
    private static final MinecraftChannelIdentifier RSPM_PACK_CHANNEL =
            MinecraftChannelIdentifier.from("rspm:pack");

    private final ProxyServer proxy;
    private final Logger slf4j;
    private final Path dataDir;

    private VelocityProxyLogger logger;
    private RspmVelocityConfig config;
    private MagmaguyRspClient httpClient;
    private NetworkSync sync;
    private GeyserBinder bedrock;
    private Path advertCacheFile;

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

        // Register the rspm:pack plugin-messaging channel and warm the cache from disk
        // so a proxy restart doesn't lose advertisements before backends re-advertise.
        // The cache is the Bedrock-pack fallback consumed by GeyserBinder when
        // NetworkSync hasn't produced a merged pack yet.
        this.advertCacheFile = dataDir.resolve("known-backends.properties");
        proxy.getChannelRegistrar().register(RSPM_PACK_CHANNEL);
        PackAdvertCache.load(advertCacheFile);

        // First poll after 5s so the rest of the proxy / Geyser finishes startup; then every 30s.
        this.sync.start(5_000L, 30_000L);
        logger.info("RSPM proxy plugin started (network-key=" + effectiveKey + "). Java pack push is handled by backends; this proxy plugin is Bedrock-only.");
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

    private void onMergedPackReady(MergedPack pack) {
        // GeyserBinder reads sync.current() lazily so it doesn't need an explicit handle,
        // but we still call its callback so the binder is consistent.
        if (bedrock != null) bedrock.onMergedPackReady(pack);
        logger.info("Merged pack ready at " + pack.url() + " (sha1 " + pack.sha1Hex() + ")");
    }

    /**
     * Receive {@code rspm:pack} advertisements pushed by backends via
     * {@code Player.sendPluginMessage} from {@code PackAdvertiser}. Payload is three
     * length-prefixed UTF strings — backend UUID, pack URL, SHA-1 hex — written with
     * {@link java.io.DataOutputStream#writeUTF}.
     *
     * <p>Source check: ignore messages whose source isn't a {@link ServerConnection},
     * i.e. messages forged by clients on the same channel. Result is marked
     * {@code handled} so Velocity doesn't blindly forward the message anywhere else.</p>
     */
    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(RSPM_PACK_CHANNEL)) return;
        ChannelMessageSource source = event.getSource();
        if (!(source instanceof ServerConnection)) {
            // Drop client-originating spoofs.
            event.setResult(PluginMessageEvent.ForwardResult.handled());
            return;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(event.getData()))) {
            String backendUuid = in.readUTF();
            String url = in.readUTF();
            String sha1Hex = in.readUTF();
            byte[] sha1 = hexToBytes(sha1Hex);
            PackAdvertCache.register(backendUuid, url, sha1);
            if (advertCacheFile != null) PackAdvertCache.save(advertCacheFile);
            logger.info("[plugin-msg] cached pack advert from backend " + backendUuid + " -> " + url);
        } catch (Exception e) {
            logger.warn("[plugin-msg] Failed to parse rspm:pack advert", e);
        }
        // Always claim the message as ours so it isn't bounced onward.
        event.setResult(PluginMessageEvent.ForwardResult.handled());
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null || hex.isEmpty()) return new byte[0];
        int len = hex.length();
        if ((len & 1) != 0) return new byte[0];
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = Character.digit(hex.charAt(i), 16);
            int lo = Character.digit(hex.charAt(i + 1), 16);
            if (hi < 0 || lo < 0) return new byte[0];
            out[i / 2] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
