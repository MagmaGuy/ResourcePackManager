package com.magmaguy.resourcepackmanager.proxy;

import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.event.EventRegistrar;
import org.geysermc.geyser.api.event.bedrock.SessionLoadResourcePacksEvent;
import org.geysermc.geyser.api.pack.PackCodec;
import org.geysermc.geyser.api.pack.ResourcePack;

/**
 * Bedrock-side glue. Subscribes to Geyser's {@link SessionLoadResourcePacksEvent}
 * via the proxy's Geyser API and registers a {@code PackCodec.url(...)} pointing at
 * the current network-merged pack URL. Geyser fetches the URL itself on the proxy
 * JVM and serves the bytes to Bedrock clients via the Bedrock protocol.
 *
 * <p>Updates the registered URL when {@link #onMergedPackReady(MergedPack)} is called
 * by {@link NetworkSync}. Per-session registration uses the latest URL in {@link #current}.
 *
 * <p>This class is platform-neutral within the Geyser ecosystem — the same instance
 * works on Geyser-Velocity, Geyser-BungeeCord, Geyser-Waterfall, Geyser-Standalone,
 * etc. The proxy plugin instantiates it once and gives it the platform-specific
 * {@link EventRegistrar} (typically {@code EventRegistrar.of(thisPluginInstance)}).
 */
public final class GeyserBinder {

    private final ProxyLogger logger;
    private final EventRegistrar registrar;
    private volatile MergedPack current;
    private volatile boolean registered;

    public GeyserBinder(ProxyLogger logger, EventRegistrar registrar) {
        this.logger = logger;
        this.registrar = registrar;
    }

    /**
     * Subscribe to {@link SessionLoadResourcePacksEvent}. Idempotent. Safe to call
     * even if Geyser is briefly unreachable — exceptions are caught and logged so
     * the proxy plugin's startup doesn't fail.
     */
    public void register() {
        if (registered) return;
        try {
            GeyserApi.api().eventBus().subscribe(
                    registrar,
                    SessionLoadResourcePacksEvent.class,
                    this::onSession);
            registered = true;
            logger.info("GeyserBinder registered — Bedrock clients will receive the network merged pack on session load.");
        } catch (Throwable t) {
            logger.warn("Failed to register GeyserBinder; Bedrock clients will not receive the network pack until restart with a compatible Geyser.", t);
        }
    }

    /**
     * Unsubscribe all our Geyser event subscriptions. Called from proxy-plugin shutdown
     * so a hot reload doesn't accumulate duplicate subscribers.
     */
    public void unregister() {
        if (!registered) return;
        try {
            GeyserApi.api().eventBus().unregisterAll(registrar);
        } catch (Throwable ignored) {
            // best-effort — Geyser may already be torn down
        }
        registered = false;
    }

    /**
     * Called by {@link NetworkSync} when a new merged pack is published. Subsequent
     * Bedrock sessions will see the new URL.
     */
    public void onMergedPackReady(MergedPack pack) {
        this.current = pack;
    }

    private void onSession(SessionLoadResourcePacksEvent event) {
        MergedPack pack = current;
        if (pack == null) {
            // NetworkSync hasn't produced a merged pack yet — most commonly because
            // the server-side /rsp/network/<key>/manifest endpoint isn't shipped (the
            // manifest poll throws UnsupportedOperationException and bails). Without
            // a fallback Bedrock players would get no RPM pack at all in network mode.
            // Try the plugin-message advertisement cache populated by backends via the
            // rspm:pack channel.
            PackAdvertCache.Advert advert = PackAdvertCache.getPrimary();
            if (advert == null) return;
            try {
                event.register(ResourcePack.create(PackCodec.url(advert.url())));
                logger.info("[fallback] Bedrock pack served from " + advert.url()
                        + " (backend " + advert.backendUuid()
                        + ", via plugin-message cache; will be replaced when the network-merged pack endpoint ships)");
            } catch (Throwable t) {
                logger.warn("Failed to register fallback pack URL for Bedrock session: " + advert.url(), t);
            }
            return;
        }
        try {
            event.register(ResourcePack.create(PackCodec.url(pack.url())));
        } catch (Throwable t) {
            logger.warn("Failed to register pack URL for Bedrock session: " + pack.url(), t);
        }
    }
}
