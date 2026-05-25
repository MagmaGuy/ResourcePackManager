package com.magmaguy.resourcepackmanager.proxy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.magmaguy.resourcepackmanager.http.MagmaguyRspClient.ManifestResult;
import com.magmaguy.resourcepackmanager.http.MagmaguyRspClient.ManifestResult.Entry;
import com.magmaguy.resourcepackmanager.http.PackHttpServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Proxy-side poller that discovers backend pack URLs by querying each backend's
 * always-on {@link PackHttpServer#METADATA_PATH} endpoint. Replaces the previous
 * magmaguy.com manifest stub (and the plugin-messaging cache it relied on for a
 * fallback): the proxy already knows its backend list via the platform-specific
 * {@link BackendListProvider}, so this needs zero shared infrastructure.
 *
 * <p>One instance per proxy. Started by the proxy plugin's enable hook; runs the
 * platform's scheduler at a fixed interval (default 60s). Each cycle hits every
 * backend once with a 2-second timeout; backends whose endpoint isn't reachable
 * (server down, RPM not loaded, port blocked) or whose metadata has a null
 * {@code url} field are silently skipped — the proxy plugin gracefully degrades
 * until the backend catches up.</p>
 *
 * <p>The aggregated result is stored in a volatile {@link AtomicReference}
 * holding a {@link ManifestResult} — same shape the previous
 * magmaguy.com-manifest code path used so {@code NetworkSync} can consume it
 * with no code change beyond switching its data source.</p>
 *
 * <p>This class is platform-neutral — it depends only on the JDK and Gson.</p>
 */
public final class BackendMetadataPoller {

    /** Per-backend HTTP timeout. Generous enough for a tiny JSON body over LAN. */
    private static final Duration PER_BACKEND_TIMEOUT = Duration.ofSeconds(2);

    private final ProxyLogger logger;
    private final ProxySchedulerAdapter scheduler;
    private final BackendListProvider backendList;
    private final int metadataPort;
    private final String expectedNetworkKey;
    private final HttpClient http;
    private final AtomicReference<ManifestResult> latest =
            new AtomicReference<>(new ManifestResult(List.of()));

    private volatile ProxySchedulerAdapter.Cancellable pollTask;

    /**
     * @param logger             platform-neutral log sink
     * @param scheduler          platform-neutral scheduler (sync or async; the poll cycle
     *                           is fully async-safe — it only mutates {@link #latest})
     * @param backendList        source of the backend list (Velocity/Bungee adapter)
     * @param metadataPort       TCP port to hit on each backend for the metadata route
     *                           (default 25567, overridable via proxy config)
     * @param expectedNetworkKey if non-null/non-blank, backends whose
     *                           {@code networkKey} field doesn't match are dropped. Lets
     *                           one proxy filter out a shared backend that's part of a
     *                           different RPM network. Pass null/blank to accept any.
     */
    public BackendMetadataPoller(ProxyLogger logger,
                                 ProxySchedulerAdapter scheduler,
                                 BackendListProvider backendList,
                                 int metadataPort,
                                 String expectedNetworkKey) {
        this.logger = logger;
        this.scheduler = scheduler;
        this.backendList = backendList;
        this.metadataPort = metadataPort;
        this.expectedNetworkKey = expectedNetworkKey;
        this.http = HttpClient.newBuilder()
                .connectTimeout(PER_BACKEND_TIMEOUT)
                .build();
    }

    /**
     * Start the polling loop. Returns immediately. First poll happens after
     * {@code initialDelayMillis}; subsequent polls every {@code intervalMillis}.
     */
    public void start(long initialDelayMillis, long intervalMillis) {
        if (pollTask != null) return;
        logger.info("BackendMetadataPoller starting (metadata port " + metadataPort
                + ", poll interval " + intervalMillis + " ms)");
        pollTask = scheduler.scheduleRepeating(this::pollOnce, initialDelayMillis, intervalMillis);
    }

    /** Cancel the polling loop. Called on proxy shutdown. */
    public void stop() {
        ProxySchedulerAdapter.Cancellable task = pollTask;
        if (task != null) {
            task.cancel();
            pollTask = null;
        }
    }

    /**
     * The most recent aggregated manifest assembled from per-backend metadata
     * responses. Never null; returns an empty {@link ManifestResult} before the
     * first successful poll. Backends whose {@code url} field is null are
     * filtered out — the result only contains backends that have a pack ready
     * to mix.
     */
    public ManifestResult getLatestManifest() {
        return latest.get();
    }

    /**
     * One poll cycle. Walks every backend in order and hits its metadata
     * endpoint; aggregates the successful responses into a new
     * {@link ManifestResult} and publishes it via {@link #latest}.
     *
     * <p>Failures (timeout, non-2xx, JSON parse error, null url field, network-key
     * mismatch) are logged at debug level only when the previous cycle's set
     * differed — keeps the proxy log quiet during steady state. A single
     * info-level summary line is emitted only when the aggregate changes.</p>
     */
    void pollOnce() {
        List<BackendListProvider.Backend> backends = safeListBackends();
        if (backends.isEmpty()) return;

        long now = System.currentTimeMillis();
        List<Entry> entries = new ArrayList<>(backends.size());
        for (BackendListProvider.Backend b : backends) {
            Entry entry = pollBackend(b, now);
            if (entry != null) entries.add(entry);
        }

        ManifestResult previous = latest.get();
        ManifestResult next = new ManifestResult(entries);
        latest.set(next);
        if (!sameEntrySet(previous, next)) {
            logger.info("Backend metadata refreshed: " + entries.size() + " backend(s) with packs ready"
                    + " (out of " + backends.size() + " configured).");
        }
    }

    private List<BackendListProvider.Backend> safeListBackends() {
        try {
            return backendList.listBackends();
        } catch (Throwable t) {
            logger.warn("BackendListProvider threw; skipping this poll cycle.", t);
            return List.of();
        }
    }

    /**
     * Hit one backend and parse its metadata response into an {@link Entry},
     * or return {@code null} if anything went wrong (silently — we don't want
     * a single down backend to flood the proxy log on every poll).
     */
    private Entry pollBackend(BackendListProvider.Backend b, long now) {
        String url = "http://" + b.host() + ":" + metadataPort + PackHttpServer.METADATA_PATH;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(PER_BACKEND_TIMEOUT)
                .GET()
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;
            JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
            // network-key filter: only accept backends explicitly tagged with the same key
            if (expectedNetworkKey != null && !expectedNetworkKey.isBlank()) {
                JsonElement nk = json.get("networkKey");
                if (nk == null || nk.isJsonNull()) return null;
                if (!expectedNetworkKey.equals(nk.getAsString())) return null;
            }
            String backendUuid = readNullableString(json, "uuid");
            String packUrl = readNullableString(json, "url");
            String sha1 = readNullableString(json, "sha1");
            // Without a URL the backend has nothing for the proxy to mix in — skip.
            if (packUrl == null) return null;
            // UUID is used as the dedupe key in NetworkSync's hasManifestChanged().
            // If the backend hasn't picked one yet (very first boot), substitute the
            // backend name so the entry still has a stable identifier across polls.
            if (backendUuid == null) backendUuid = "backend-" + b.name();
            if (sha1 == null) sha1 = ""; // mixer doesn't need it; sha1Hex tracking only
            return new Entry(backendUuid, packUrl, sha1, 0, now);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Throwable t) {
            // Connect refused, parse failure, etc. — backend isn't ready or RPM
            // isn't loaded. Don't log per backend per cycle; that would be spam.
            return null;
        }
    }

    private static String readNullableString(JsonObject json, String key) {
        JsonElement el = json.get(key);
        if (el == null || el.isJsonNull()) return null;
        String s = el.getAsString();
        return (s == null || s.isEmpty()) ? null : s;
    }

    /**
     * Cheap structural comparison so we only log on actual change. Compares the
     * (uuid, url, sha1) of each entry — order-insensitive.
     */
    private static boolean sameEntrySet(ManifestResult a, ManifestResult b) {
        if (a == null || b == null) return false;
        List<Entry> la = a.entries();
        List<Entry> lb = b.entries();
        if (la.size() != lb.size()) return false;
        outer:
        for (Entry ea : la) {
            for (Entry eb : lb) {
                if (ea.uuid().equals(eb.uuid())
                        && ea.url().equals(eb.url())
                        && ea.sha1().equals(eb.sha1())) {
                    continue outer;
                }
            }
            return false;
        }
        return true;
    }
}
