package com.magmaguy.resourcepackmanager.proxy;

import com.magmaguy.resourcepackmanager.http.MagmaguyRspClient.ManifestResult;
import com.magmaguy.resourcepackmanager.http.MagmaguyRspClient.ManifestResult.Entry;
import com.magmaguy.resourcepackmanager.http.PackHttpServer;
import com.magmaguy.resourcepackmanager.mixer.engine.MixEngine;
import com.magmaguy.resourcepackmanager.mixer.engine.MixInput;
import com.magmaguy.resourcepackmanager.mixer.engine.MixOutput;
import com.magmaguy.resourcepackmanager.mixer.engine.MixerLogger;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Proxy-side orchestrator. Pulls the current backend manifest from a
 * {@link Supplier} (typically backed by {@link BackendMetadataPoller}),
 * downloads each backend's pack, runs the shared {@link MixEngine} to merge
 * them, self-hosts the merged pack on a local {@link PackHttpServer}, and
 * notifies a callback when a new merged pack is ready. The callback is
 * responsible for actually pushing to clients (Bedrock via Geyser's session
 * subscriber; Java pack push is handled by individual backends).
 *
 * <p>One instance per network-key. The proxy plugin instantiates it once per
 * startup with the resolved network-key from config and starts the polling
 * loop.</p>
 *
 * <p>This class is platform-neutral — it does not import anything from
 * {@code org.bukkit}, {@code com.velocitypowered}, {@code net.md_5.bungee}, or
 * {@code org.geysermc}. Velocity and Bungee entrypoints wire it up with their
 * own {@link ProxyLogger} and {@link ProxySchedulerAdapter} implementations.</p>
 */
public final class NetworkSync {

    /**
     * Entries older than this are considered stale and excluded from the mix.
     * The {@link BackendMetadataPoller} stamps {@code lastSeenMillis} on every
     * successful poll, so a fresh entry stays fresh as long as the backend is
     * reachable. 8 hours is comfortably longer than any sensible
     * {@code BackendMetadataPoller} interval but short enough that a
     * permanently-departed backend stops contributing within a day.
     */
    static final long STALE_THRESHOLD_MILLIS = 8L * 60L * 60L * 1000L;

    private final ProxyLogger logger;
    private final ProxySchedulerAdapter scheduler;
    private final Supplier<ManifestResult> manifestSource;
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
     * Per-uuid last-seen sha1 for change detection. Populated from the manifest on
     * each successful re-mix. Compared against the next poll's map to decide whether
     * to re-mix or skip.
     */
    private final Map<String, String> lastSeenSha1 = new ConcurrentHashMap<>();

    public NetworkSync(
            ProxyLogger logger,
            ProxySchedulerAdapter scheduler,
            Supplier<ManifestResult> manifestSource,
            MixerLogger mixerLogger,
            File workingDir,
            String networkKey,
            int selfHostPort,
            String selfHostExternalHost,
            Consumer<MergedPack> onMergedPackReady) {
        this.logger = logger;
        this.scheduler = scheduler;
        this.manifestSource = manifestSource;
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

    /** Mix + publish. Synchronous on the scheduler's async thread. */
    private void pollOnce() {
        try {
            ManifestResult manifest = manifestSource.get();
            if (manifest == null) return;

            // Drop entries whose backends haven't checked in recently. Even though
            // BackendMetadataPoller stamps fresh entries every cycle, this guard
            // protects against a stale snapshot lingering if the poller itself
            // ever stalls.
            List<Entry> active = filterStale(manifest.entries(), System.currentTimeMillis(), STALE_THRESHOLD_MILLIS);
            if (active.isEmpty()) {
                return;
            }

            // Compute current set + sha1 map.
            Map<String, String> currentSha1 = active.stream()
                    .collect(Collectors.toMap(Entry::uuid, Entry::sha1));

            // Change detection: have any UUIDs been added/removed, or has any sha1 shifted?
            if (!hasManifestChanged(lastSeenSha1, currentSha1)) {
                return; // no changes; nothing to do this cycle
            }

            logger.info("Network manifest changed (" + active.size() + " active backends); re-mixing.");

            // Download each pack to a per-uuid file in workingDir/downloads/.
            File downloadsDir = new File(workingDir, "downloads");
            downloadsDir.mkdirs();
            List<File> orderedPacks = new ArrayList<>();
            for (Entry e : active) {
                File dest = new File(downloadsDir, e.uuid() + ".zip");
                try {
                    downloadPack(e.url(), dest);
                    orderedPacks.add(dest);
                } catch (IOException ioex) {
                    logger.warn("Skipping backend " + e.uuid() + " - download failed: " + ioex.getMessage());
                }
            }
            if (orderedPacks.isEmpty()) {
                logger.warn("All backend pack downloads failed; nothing to mix.");
                return;
            }

            // Run the platform-neutral mixer.
            File mixerWorkDir = new File(workingDir, "mixer-scratch");
            File mixerOutDir = new File(workingDir, "mixer-output");
            mixerWorkDir.mkdirs();
            mixerOutDir.mkdirs();
            MixInput input = new MixInput(orderedPacks, mixerWorkDir, mixerOutDir, workingDir, "rspm-network", false);
            MixOutput out = mixer.run(input);

            // Publish on the proxy's own self-host server.
            String publishedUrl = startOrRefreshSelfHost(out.mergedZip());
            if (publishedUrl == null) {
                logger.warn("Failed to self-host merged pack on port " + selfHostPort + ".");
                return;
            }

            MergedPack pack = new MergedPack(publishedUrl, out.sha1Hex(), out.sha1Bytes(), MergedPack.NETWORK_PACK_UUID);
            current = pack;
            lastSeenSha1.clear();
            lastSeenSha1.putAll(currentSha1);
            onMergedPackReady.accept(pack);
            logger.info("Merged pack published at " + publishedUrl);

            // Polish: prune any per-uuid download files that no longer correspond to an
            // active backend. Cheap to do here; keeps the downloads dir from growing
            // unbounded across long uptimes as backends rotate in/out.
            cleanupDownloads(downloadsDir, currentSha1.keySet());

        } catch (Exception e) {
            logger.warn("Network sync poll failed", e);
        }
    }

    /** The most recently published merged pack, or {@code null} if none yet. */
    public MergedPack current() {
        return current;
    }

    // ------------------------------------------------------------------
    // Helpers (package-private static where useful for unit testing)
    // ------------------------------------------------------------------

    /**
     * Pure change-detection over two uuid->sha1 maps. Returns true iff either map
     * contains a uuid the other doesn't, or any shared uuid maps to a different sha1.
     *
     * <p>Extracted as a static helper so {@code NetworkSyncTest} can exercise it
     * directly without needing to mock the HTTP client.</p>
     */
    static boolean hasManifestChanged(Map<String, String> previous, Map<String, String> current) {
        if (previous == current) return false;
        if (previous.size() != current.size()) return true;
        for (Map.Entry<String, String> e : current.entrySet()) {
            if (!Objects.equals(e.getValue(), previous.get(e.getKey()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Filter the manifest entries to those seen within {@code staleThresholdMillis}
     * of {@code now}. Pure function; extracted as a static helper for direct unit
     * testing.
     */
    static List<Entry> filterStale(List<Entry> entries, long now, long staleThresholdMillis) {
        long cutoff = now - staleThresholdMillis;
        List<Entry> out = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            if (e.lastSeenMillis() >= cutoff) out.add(e);
        }
        return out;
    }

    private void downloadPack(String url, File dest) throws IOException {
        // Use the JDK's built-in HttpClient so we don't drag Apache HttpComponents
        // onto resourcepackmanager-proxy-common's classpath for a one-shot GET.
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<Path> response;
        try {
            response = http.send(
                    HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofFile(dest.toPath()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while downloading " + url, e);
        }
        if (response.statusCode() != 200) {
            throw new IOException("Pack download " + url + " returned HTTP " + response.statusCode());
        }
    }

    /**
     * Start the local self-host HTTP server if it isn't running yet; if it already is,
     * reuse it. The server reads the file fresh on each request, so the URL is
     * stable across re-mixes — clients see new bytes without us needing to bounce
     * the port (and the pack UUID is also stable per {@link MergedPack#NETWORK_PACK_UUID}).
     */
    private String startOrRefreshSelfHost(File mergedZip) {
        if (selfHostServer != null) {
            return selfHostServer.urlOn(resolveExternalHost());
        }
        try {
            PackHttpServer s = PackHttpServer.start(mergedZip, selfHostPort, "/network.zip");
            selfHostServer = s;
            String url = s.urlOn(resolveExternalHost());
            logger.info("Self-hosting merged pack at " + url);
            return url;
        } catch (IOException e) {
            logger.warn("Self-host fallback failed (port " + selfHostPort + " in use?)", e);
            return null;
        }
    }

    private String resolveExternalHost() {
        if (selfHostExternalHost != null && !selfHostExternalHost.isBlank()) return selfHostExternalHost;
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            return "localhost";
        }
    }

    /**
     * Delete any per-uuid {@code <uuid>.zip} in {@code downloadsDir} whose uuid is
     * no longer in the current active set. Polish only; failure is non-fatal.
     */
    private void cleanupDownloads(File downloadsDir, Set<String> activeUuids) {
        File[] files = downloadsDir.listFiles();
        if (files == null) return;
        Set<String> keep = new HashSet<>(activeUuids);
        for (File f : files) {
            String name = f.getName();
            if (!name.endsWith(".zip")) continue;
            String uuid = name.substring(0, name.length() - ".zip".length());
            if (!keep.contains(uuid)) {
                // Best-effort delete — if it fails (e.g. file in use), we'll try again
                // next cycle.
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }
}
