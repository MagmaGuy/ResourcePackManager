package com.magmaguy.resourcepackmanager.velocity;

import com.magmaguy.resourcepackmanager.proxy.ProxyLogger;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.event.EventRegistrar;
import org.geysermc.geyser.api.event.bedrock.SessionDisconnectEvent;
import org.geysermc.geyser.api.event.bedrock.SessionLoginEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomBlocksEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserShutdownEvent;
import org.geysermc.geyser.api.extension.Extension;

import java.lang.reflect.InvocationTargetException;

/**
 * Relays Geyser lifecycle/session events to the bundled RSPM extension on
 * Velocity.
 *
 * <p>Geyser normally registers an extension's {@code @Subscribe} methods when
 * the extension is enabled. On Velocity, Geyser is constructed on Floodgate's
 * task executor before its later initialization task; in that bootstrap shape
 * the universal RSPM jar is loaded as both a Velocity plugin and a Geyser
 * extension, but the extension's reflected subscribers are not called. The
 * proxy plugin's ordinary programmatic Geyser subscriptions do work (the pack
 * binder uses the same public API), so this small relay guarantees that the
 * extension is activated before Geyser freezes its custom registries and that
 * session hooks remain connected.
 *
 * <p>The extension handlers are deliberately idempotent. If a future Geyser
 * release also delivers the automatically registered callbacks, receiving the
 * same event through both paths is harmless.
 */
final class VelocityGeyserBridgeLifecycleRelay {
    private static final String EXTENSION_ID = "resourcepackmanagergeyserbridge";

    private final ProxyLogger logger;
    private final EventRegistrar registrar = EventRegistrar.of(new Object());
    private volatile boolean registered;
    private volatile boolean warnedInvocationFailure;

    VelocityGeyserBridgeLifecycleRelay(ProxyLogger logger) {
        this.logger = logger;
    }

    void register() {
        if (registered) {
            return;
        }

        try {
            GeyserApi api = GeyserApi.api();
            api.eventBus().subscribe(registrar, GeyserDefineCustomBlocksEvent.class,
                    event -> invoke("onDefineCustomBlocks", GeyserDefineCustomBlocksEvent.class, event));
            api.eventBus().subscribe(registrar, GeyserPostInitializeEvent.class,
                    event -> invoke("onPostInitialize", GeyserPostInitializeEvent.class, event));
            api.eventBus().subscribe(registrar, SessionLoginEvent.class,
                    event -> invoke("onSessionJoin", SessionLoginEvent.class, event));
            api.eventBus().subscribe(registrar, SessionDisconnectEvent.class,
                    event -> invoke("onSessionQuit", SessionDisconnectEvent.class, event));
            api.eventBus().subscribe(registrar, GeyserShutdownEvent.class,
                    event -> invoke("onShutdown", GeyserShutdownEvent.class, event));
            registered = true;
        } catch (Throwable throwable) {
            try {
                GeyserApi.api().eventBus().unregisterAll(registrar);
            } catch (Throwable ignored) {
                // Best-effort rollback when only some subscriptions succeeded.
            }
            logger.warn("Failed to register the Velocity-to-Geyser bridge lifecycle relay; custom Bedrock entities may require a proxy restart with a compatible Geyser build.", throwable);
        }
    }

    void unregister() {
        if (!registered) {
            return;
        }
        try {
            invokeNoArg("shutdown");
            GeyserApi.api().eventBus().unregisterAll(registrar);
        } catch (Throwable ignored) {
            // Geyser may already be shutting down.
        }
        registered = false;
    }

    private void invokeNoArg(String methodName) {
        try {
            Extension extension = GeyserApi.api().extensionManager().extension(EXTENSION_ID);
            if (extension != null) {
                extension.getClass().getMethod(methodName).invoke(extension);
            }
        } catch (InvocationTargetException exception) {
            warnInvocationFailure(methodName, exception.getCause() == null ? exception : exception.getCause());
        } catch (ReflectiveOperationException | LinkageError exception) {
            warnInvocationFailure(methodName, exception);
        }
    }

    private <T> void invoke(String methodName, Class<T> eventType, T event) {
        try {
            Extension extension = GeyserApi.api().extensionManager().extension(EXTENSION_ID);
            if (extension == null) {
                // The installer has already explained that a restart is needed
                // when no extension was present for this boot.
                return;
            }
            extension.getClass().getMethod(methodName, eventType).invoke(extension, event);
        } catch (InvocationTargetException exception) {
            warnInvocationFailure(methodName, exception.getCause() == null ? exception : exception.getCause());
        } catch (ReflectiveOperationException | LinkageError exception) {
            warnInvocationFailure(methodName, exception);
        }
    }

    private void warnInvocationFailure(String methodName, Throwable throwable) {
        if (warnedInvocationFailure) {
            return;
        }
        warnedInvocationFailure = true;
        logger.warn("Could not relay Geyser event to the RSPM extension method " + methodName
                + "; the queued universal extension will be retried after the next proxy restart.", throwable);
    }
}
