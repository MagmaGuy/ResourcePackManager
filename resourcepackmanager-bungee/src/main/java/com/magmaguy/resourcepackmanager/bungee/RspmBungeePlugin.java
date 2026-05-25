package com.magmaguy.resourcepackmanager.bungee;

import com.magmaguy.resourcepackmanager.http.MagmaguyRspClient;
import com.magmaguy.resourcepackmanager.mixer.engine.MixerLogger;
import com.magmaguy.resourcepackmanager.proxy.GeyserBinder;
import com.magmaguy.resourcepackmanager.proxy.MergedPack;
import com.magmaguy.resourcepackmanager.proxy.NetworkSync;
import com.magmaguy.resourcepackmanager.proxy.PackAdvertCache;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;
import org.geysermc.geyser.api.event.EventRegistrar;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.nio.file.Path;

public final class RspmBungeePlugin extends Plugin {

    /**
     * Plugin-messaging channel backends use to advertise their pack URL+sha1 to the
     * proxy. Mirrors {@code PackAdvertiser.CHANNEL} on the backend.
     */
    static final String RSPM_PACK_CHANNEL = "rspm:pack";

    private BungeeProxyLogger logger;
    private RspmBungeeConfig config;
    private MagmaguyRspClient httpClient;
    private NetworkSync sync;
    private GeyserBinder bedrock;
    private Path advertCacheFile;

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

        // Register the rspm:pack plugin-messaging channel and warm the cache from disk
        // so a proxy restart doesn't lose advertisements before backends re-advertise.
        // The cache is the Bedrock-pack fallback consumed by GeyserBinder when
        // NetworkSync hasn't produced a merged pack yet.
        this.advertCacheFile = getDataFolder().toPath().resolve("known-backends.properties");
        getProxy().registerChannel(RSPM_PACK_CHANNEL);
        PackAdvertCache.load(advertCacheFile);
        getProxy().getPluginManager().registerListener(this, new PackAdvertListener());

        // First poll after 5s so the rest of the proxy / Geyser finishes startup; then every 30s.
        this.sync.start(5_000L, 30_000L);
        logger.info("RSPM proxy plugin started (network-key=" + effectiveKey + "). Java pack push is handled by backends; this proxy plugin is Bedrock-only.");
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
        logger.info("Merged pack ready at " + pack.url() + " (sha1 " + pack.sha1Hex() + ")");
    }

    /**
     * Receive {@code rspm:pack} advertisements pushed by backends via Bukkit's
     * {@code Player.sendPluginMessage} from {@code PackAdvertiser}. Payload is three
     * length-prefixed UTF strings — backend UUID, pack URL, SHA-1 hex — written with
     * {@link java.io.DataOutputStream#writeUTF}.
     *
     * <p>Source check: only accept messages originating from a {@link Server}
     * (backend connection), not from clients (drops same-channel spoof attempts).
     * The event is cancelled either way so Bungee doesn't forward the message
     * on to the client.</p>
     */
    public final class PackAdvertListener implements Listener {
        @EventHandler
        public void onPluginMessage(PluginMessageEvent event) {
            if (!event.getTag().equals(RSPM_PACK_CHANNEL)) return;
            if (!(event.getSender() instanceof Server)) {
                event.setCancelled(true);
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
                getLogger().warning("[plugin-msg] Failed to parse rspm:pack advert: " + e.getMessage());
            }
            // Don't forward backend->proxy plugin messages onward to clients.
            event.setCancelled(true);
        }
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
