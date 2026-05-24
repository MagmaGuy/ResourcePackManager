package com.magmaguy.rspm.proxy;

import com.magmaguy.rspm.http.MagmaguyRspClient;
import com.magmaguy.rspm.http.PackHttpServer;
import com.magmaguy.rspm.mixer.MixEngine;
import com.magmaguy.rspm.mixer.MixerLogger;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Proxy-side orchestrator. Polls magmaguy.com for the current set of backend pack URLs
 * registered under our network-key, downloads each, runs the shared {@link MixEngine}
 * to merge them, publishes the merged pack (uploaded to magmaguy.com or self-hosted on
 * upload failure), and notifies a callback when a new merged pack is ready. The callback
 * is responsible for actually pushing to clients (Java via the proxy's pack-offer API,
 * Bedrock via Geyser's session subscriber).
 *
 * <p>One instance per network-key. The proxy plugin instantiates it once per startup
 * with the resolved network-key from config and starts the polling loop.</p>
 *
 * <p>This class is platform-neutral — it does not import anything from
 * {@code org.bukkit}, {@code com.velocitypowered}, {@code net.md_5.bungee}, or
 * {@code org.geysermc}. Velocity and Bungee entrypoints in later phases wire it up
 * with their own {@link ProxyLogger} and {@link ProxySchedulerAdapter}
 * implementations.</p>
 */
public final class NetworkSync {

    private final ProxyLogger logger;
    private final ProxySchedulerAdapter scheduler;
    private final MagmaguyRspClient client;
    private final MixEngine mixer;
    private final File workingDir;
    private final String networkKey;
    private final int selfHostPort;
    private final String selfHostExternalHost;
    private final Consumer<MergedPack> onMergedPackReady;

    private volatile ProxySchedulerAdapter.Cancellable pollTask;
    private volatile PackHttpServer selfHostServer;
    private volatile MergedPack current;

    /**
     * Per-uuid last-seen sha1 for change detection. Task 4.2 will populate this from
     * the manifest response to decide whether to re-mix or skip.
     */
    private final Map<String, String> lastSeenSha1 = new ConcurrentHashMap<>();

    public NetworkSync(
            ProxyLogger logger,
            ProxySchedulerAdapter scheduler,
            MagmaguyRspClient client,
            MixerLogger mixerLogger,
            File workingDir,
            String networkKey,
            int selfHostPort,
            String selfHostExternalHost,
            Consumer<MergedPack> onMergedPackReady) {
        this.logger = logger;
        this.scheduler = scheduler;
        this.client = client;
        this.mixer = new MixEngine(mixerLogger);
        this.workingDir = workingDir;
        this.networkKey = networkKey;
        this.selfHostPort = selfHostPort;
        this.selfHostExternalHost = selfHostExternalHost;
        this.onMergedPackReady = onMergedPackReady;
    }

    /**
     * Start the polling loop. Returns immediately. First poll happens after a short
     * initial delay so the proxy can finish booting; subsequent polls every
     * {@code intervalMillis}.
     */
    public void start(long initialDelayMillis, long intervalMillis) {
        if (pollTask != null) return;
        logger.info("NetworkSync starting for network-key " + networkKey
                + " (poll interval " + intervalMillis + " ms)");
        pollTask = scheduler.scheduleRepeating(this::pollOnce, initialDelayMillis, intervalMillis);
    }

    /**
     * Cancel the polling loop and close the self-host server. Called on proxy shutdown.
     */
    public void stop() {
        ProxySchedulerAdapter.Cancellable task = pollTask;
        if (task != null) {
            task.cancel();
            pollTask = null;
        }
        PackHttpServer server = selfHostServer;
        if (server != null) {
            try {
                server.close();
            } catch (Exception ignored) {
                // close-on-shutdown failures are not actionable
            }
            selfHostServer = null;
        }
    }

    /** Manifest poll + mix + publish. Synchronous on the scheduler's async thread. */
    private void pollOnce() {
        try {
            // fetchNetworkManifest is still a stub on MagmaguyRspClient — it throws
            // UnsupportedOperationException. We catch and stay silent so a missing
            // server-side implementation doesn't spam the log every interval.
            //
            // Task 4.2 will add real manifest-change detection and the
            // download + mix + publish pipeline. For Task 4.1 we just structure the
            // entry point and verify the build wires together.
            client.fetchNetworkManifest(networkKey);
        } catch (UnsupportedOperationException e) {
            // Expected until magmaguy.com server adds network endpoints. Don't log at
            // every interval — would drown other output.
        } catch (Exception e) {
            logger.warn("Failed to poll network manifest", e);
        }
    }

    /** The most recently published merged pack, or {@code null} if none yet. */
    public MergedPack current() {
        return current;
    }
}
