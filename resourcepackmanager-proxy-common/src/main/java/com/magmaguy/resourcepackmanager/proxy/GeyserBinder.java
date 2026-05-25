package com.magmaguy.resourcepackmanager.proxy;

import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.event.EventRegistrar;
import org.geysermc.geyser.api.event.bedrock.SessionLoadResourcePacksEvent;
import org.geysermc.geyser.api.pack.PackCodec;
import org.geysermc.geyser.api.pack.ResourcePack;

/**
 * Bedrock-side glue. Subscribes to Geyser's {@link SessionLoadResourcePacksEvent}
 * via the proxy's Geyser API and registers a {@code PackCodec.path(...)} pointing
 * at the on-disk merged Bedrock pack file. Geyser serves the bytes from disk to
 * Bedrock clients via the Bedrock protocol — no HTTP server is involved on the
 * proxy.
 *
 * <p>Updates the registered file when {@link #onMergedPackReady(MergedPack)} is
 * called by {@link NetworkSync}. Per-session registration uses the latest pack
 * file in {@link #current}. The path is stable across re-merges (the merger
 * overwrites the same file atomically), so Geyser picks up new bytes on the
 * next session load.
 *
 * <p>This class is platform-neutral within the Geyser ecosystem — the same
 * instance works on Geyser-Velocity, Geyser-BungeeCord, Geyser-Waterfall,
 * Geyser-Standalone, etc. The proxy plugin instantiates it once and gives it
 * the platform-specific {@link EventRegistrar}.
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
     * Bedrock sessions will see the new file.
     */
    public void onMergedPackReady(MergedPack pack) {
        this.current = pack;
    }

    private void onSession(SessionLoadResourcePacksEvent event) {
        MergedPack pack = current;
        if (pack == null) {
            // NetworkSync hasn't completed its first merge yet (no backend has
            // produced a /bedrock.zip we could pull). Skip silently — the next
            // Bedrock session will pick up the merged pack as soon as it's ready.
            return;
        }
        java.io.File packFile = pack.packFile();
        if (packFile == null || !packFile.isFile()) {
            logger.warn("Merged Bedrock pack file is missing on disk: "
                    + (packFile == null ? "null" : packFile.getAbsolutePath()));
            return;
        }
        try {
            event.register(ResourcePack.create(PackCodec.path(packFile.toPath())));
        } catch (Throwable t) {
            logger.warn("Failed to register pack for Bedrock session from " + packFile.getAbsolutePath(), t);
        }
    }
}
