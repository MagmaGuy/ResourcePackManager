package com.magmaguy.resourcepackmanager.proxy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.magmaguy.resourcepackmanager.http.MagmaguyRspClient;
import com.magmaguy.resourcepackmanager.http.NetworkAccessToken;
import com.magmaguy.resourcepackmanager.http.NetworkKeyResolver;
import com.magmaguy.resourcepackmanager.http.PackHttpServer;
import com.magmaguy.resourcepackmanager.mixer.bedrock.BedrockMappingsMerger;
import com.magmaguy.resourcepackmanager.mixer.bedrock.BedrockPackMerger;
import com.magmaguy.resourcepackmanager.mixer.engine.MixerLogger;
import com.magmaguy.resourcepackmanager.mixer.engine.internal.ZipUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Proxy-side orchestrator. For each backend in the proxy's server list, pulls
 * {@code /bedrock.zip} and {@code /mappings.json} from the backend's
 * {@link PackHttpServer} (via strong ETags, with {@code If-Modified-Since}
 * retained for older servers), waits for the inbox to stabilize (identical hashes
 * across adjacent poll cycles — see {@link #STABLE_CYCLES_REQUIRED}), and then
 * merges every backend's contribution into a single Bedrock pack + single
 * Geyser mappings JSON.
 *
 * <p>The proxy no longer downloads Java packs, runs a Java-side mixer, runs
 * BedrockConversion, or self-hosts an HTTP server. All of that lives on the
 * backends now; the proxy is a thin file-union step that hands the merged
 * Bedrock pack to Geyser via {@code PackCodec.path}.</p>
 *
 * <p>One instance per network-key. The proxy plugin instantiates it once per
 * startup and starts the polling loop.</p>
 *
 * <p>Platform-neutral — no imports from {@code org.bukkit},
 * {@code com.velocitypowered}, {@code net.md_5.bungee}, or {@code org.geysermc}.
 * Velocity and Bungee entrypoints wire it up with their own {@link ProxyLogger}
 * and {@link ProxySchedulerAdapter} implementations.</p>
 */
public final class NetworkSync {

    /**
     * Number of consecutive identical-hash poll cycles required before we merge.
     * Set to 1: as soon as the inbox state matches across two adjacent polls,
     * merge. (The first poll always sets the baseline {@code lastInboxHashes}
     * and the second poll's match triggers the merge — so effectively "merge
     * after two polls observe the same state.")
     *
     * <p><b>Why not the previous 2-cycle gate:</b> the old value was defensive
     * paranoia against torn writes — "what if the backend is mid-regenerating
     * its zip while we poll?" In practice this can never happen: the backend's
     * {@code BedrockZip.zip} writes to a temp file then atomically renames,
     * so the {@code /bedrock.zip} HTTP route always serves either the old
     * complete zip or the new complete zip — never a half-written one.
     * Without that risk the 2-cycle gate was pure cold-start latency cost
     * (an extra full poll interval before first merge).</p>
     *
     * <p>Tradeoff with 1: if two backends regenerate at slightly different
     * times, one cycle could merge backend-A-NEW with backend-B-OLD before
     * the next cycle merges with both new. Brief (~poll interval) window of
     * stale content from one backend, then auto-corrects. Acceptable.</p>
     */
    static final int STABLE_CYCLES_REQUIRED = 1;

    /** Per-backend HTTP timeout. Tight enough that a dead backend doesn't stall a poll. */
    private static final Duration PER_BACKEND_TIMEOUT = Duration.ofSeconds(5);

    /**
     * When magmaguy.com's relay list is unreachable, don't block every 5s poll on
     * another 30s HTTP timeout. Direct backend polling still runs every cycle; only
     * the fallback relay list call is backed off.
     */
    private static final Duration RELAY_LIST_FAILURE_BACKOFF = Duration.ofMinutes(2);

    /** Lightweight endpoint-registry lookup backoff. Keeps hoster blips from stalling every poll. */
    private static final Duration ENDPOINT_LIST_FAILURE_BACKOFF = Duration.ofSeconds(30);

    /**
     * A 401 on the protected executable-update route cannot recover on the next
     * five-second poll: the proxy and backend have derived different access
     * tokens, normally because their Floodgate key.pem files differ. Keep pack
     * and mappings polling at full speed, but back this one optional route off
     * so a configuration error does not flood the console or backend HTTP server.
     */
    private static final Duration EXECUTABLE_UPDATE_AUTH_INITIAL_BACKOFF = Duration.ofMinutes(1);
    private static final Duration EXECUTABLE_UPDATE_AUTH_MAX_BACKOFF = Duration.ofMinutes(15);

    /** RFC 1123 / HTTP-date formatter for {@code If-Modified-Since}. */
    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
                    .withZone(ZoneOffset.UTC);

    private static final String MERGED_BEDROCK_ZIP_NAME = MergedOutputPublication.PACK_NAME;
    private static final String MERGED_MAPPINGS_NAME = MergedOutputPublication.MAPPINGS_NAME;
    private static final String INBOX_BEDROCK_ZIP_NAME = "Bedrock.zip";
    private static final String INBOX_MAPPINGS_NAME = "mappings.json";
    private static final String DIRECT_BACKEND_ID_FILE_NAME = ".backend-id";
    private static final String ARTIFACT_SET_MANIFEST_NAME = "rspm_artifact_set.json";
    private static final int ARTIFACT_SET_MANIFEST_VERSION = 1;
    private static final String ARTIFACT_GENERATION_FILE_NAME = ".artifact-generation";
    private static final String ETAG_SIDECAR_SUFFIX = ".etag";
    /** Valid but deliberately impossible-to-match tag used with legacy servers. */
    private static final String LEGACY_NO_ETAG_SENTINEL = "\"rspm-no-etag-v1\"";

    private final ProxyLogger logger;
    private final ProxySchedulerAdapter scheduler;
    private final BackendListProvider backendList;
    private final File workingDir;
    private final int networkHttpOffset;
    private final MixerLogger mixerLogger;
    private final File geyserPluginDir;
    private final Consumer<MergedPack> onMergedPackReady;
    private final Predicate<Path> onPluginUpdateCandidate;
    private final HttpClient http;
    /**
     * Network key for this proxy. Used to look up Bedrock-relay entries
     * uploaded by backends that this proxy can't reach directly (typical of
     * shared-hosting setups where MC ports are exposed but adjacent ports
     * aren't). May be null when Floodgate isn't installed — in that case
     * the relay-fallback path is skipped silently.
     */
    private final String networkKey;
    /** Lazily constructed client for the relay-fallback path. Null until first use. */
    private volatile MagmaguyRspClient relayClient;
    private volatile Instant nextRelayListAttemptAt = Instant.EPOCH;
    private volatile Instant nextEndpointListAttemptAt = Instant.EPOCH;
    private volatile List<MagmaguyRspClient.BedrockEndpoint> announcedEndpoints = List.of();
    private volatile List<BackendListProvider.Backend> lastSuccessfulBackends = List.of();
    /** Last authoritative relay replacement mapping: direct inbox name -> backend id. */
    private volatile Map<String, String> relayAuthorityByDirectDirectory = Map.of();

    private final File inboxRoot;
    private final File relayInboxRoot;
    private final File mergedDir;
    private final File mergedBedrockZip;
    private final File mergedMappings;
    private final File pluginUpdateCandidatesDir;

    private volatile ProxySchedulerAdapter.Cancellable pollTask;
    private volatile MergedPack current;
    private final AtomicBoolean pollInProgress = new AtomicBoolean(false);
    private final AtomicLong lifecycleGeneration = new AtomicLong();
    private final Object pollCompletionMonitor = new Object();
    private volatile boolean stopped = false;
    private volatile boolean overlapWarningFired = false;

    /** Set of file SHA-1s under inbox/ at the end of the previous poll cycle. */
    private Set<String> lastInboxHashes = new TreeSet<>();
    /** Number of consecutive poll cycles where inbox hashes matched {@link #lastInboxHashes}. */
    private int stableCount = 0;
    /** Hashes that were last successfully merged. Avoids re-merging identical state. */
    private Set<String> lastMergedHashes = null;
    /**
     * Backends whose current downloaded executable candidate was accepted by the
     * proxy update coordinator. A rejected candidate is deliberately not added:
     * the backend will answer subsequent conditional requests with 304, and that
     * cached local candidate must be offered again so transient authority failures
     * cannot stall updates forever.
     */
    private final Set<String> acceptedPluginUpdateCandidates = new TreeSet<>();

    /** Per-backend retry state for HTTP 401 on /rspm-update.jar only. */
    private final Map<String, ExecutableUpdateAuthRetry> executableUpdateAuthRetries =
            new HashMap<>();

    private record ExecutableUpdateAuthRetry(Instant nextAttemptAt, Duration delay) {}

    // --- diagnostic state ---
    // We deliberately avoided per-poll WARN logging in fetchIfChanged because
    // a transient network blip on every backend would spam the proxy console.
    // BUT — in the field we hit setups where the proxy COULD NOT REACH ANY
    // BACKEND (Velocity server-list address mismatch, Docker NAT, firewall
    // between hosts) and silently produced no merged pack. From the operator's
    // perspective: NetworkSync says "starting", then nothing. Hours of
    // wondering what's wrong. The diagnostic state below fixes that:
    //
    //   - Every fetch records a per-(backend, path) FetchOutcome with the URL
    //     attempted and what happened (HTTP code, IOException class+message,
    //     etc.). Exposed via the snapshot() accessor for /rspm status on the
    //     proxy side.
    //   - When N consecutive poll cycles produce an empty inbox AND we have
    //     backends to poll, we log a single multi-line warning summarizing
    //     every URL tried and its outcome. Once-only — gated by
    //     unreachableWarningFired — so a long-broken setup doesn't spam.

    /** Latest fetch outcome per (backend, path) — keyed by "<sanitized-name>:<path>". */
    private final java.util.concurrent.ConcurrentHashMap<String, FetchOutcome> lastFetchOutcomes =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Number of consecutive poll cycles that produced an empty inbox. */
    private int consecutiveEmptyPolls = 0;

    /**
     * After this many empty-inbox cycles, fire the "no backend reachable" warning.
     * 4 cycles × 5s default interval = 20s before the warning fires — enough that
     * a backend that's slow to start its HTTP server doesn't trigger a false alarm,
     * but fast enough that a genuinely-broken setup gets actionable feedback before
     * the operator gives up.
     */
    private static final int UNREACHABLE_WARNING_THRESHOLD_CYCLES = 4;

    /** Latch: fire the warning at most once per "stuck" period to avoid console spam. */
    private boolean unreachableWarningFired = false;

    /**
     * One backend-path fetch attempt's outcome. {@link Kind} tells the operator
     * whether the backend was unreachable at the network layer, present but
     * returning the wrong status, or working — each implies a different fix.
     */
    public record FetchOutcome(Kind kind, int httpStatus, String detail, java.time.Instant at) {
        public enum Kind {
            /** 200 OK — fetch succeeded, content saved to inbox. */
            OK_200,
            /** 304 Not Modified — backend confirmed our If-Modified-Since cache. Healthy. */
            NOT_MODIFIED_304,
            /** 404 — backend hasn't produced this file yet. Common on first boot before mix completes. */
            NOT_FOUND_404,
            /** Non-2xx/3xx/4xx response — backend is reachable but returning the wrong code. */
            UNEXPECTED_STATUS,
            /** IOException during the request — backend unreachable at network layer. */
            CONNECT_FAILED,
            /** Other exception (URI parse, etc.). */
            OTHER_ERROR
        }
    }

    public NetworkSync(
            ProxyLogger logger,
            ProxySchedulerAdapter scheduler,
            BackendListProvider backendList,
            File workingDir,
            int networkHttpOffset,
            MixerLogger mixerLogger,
            File geyserPluginDir,
            String networkKey,
            Consumer<MergedPack> onMergedPackReady,
            Predicate<Path> onPluginUpdateCandidate) {
        this.logger = logger;
        this.scheduler = scheduler;
        this.backendList = backendList;
        this.workingDir = workingDir;
        this.networkHttpOffset = networkHttpOffset;
        this.mixerLogger = mixerLogger;
        this.geyserPluginDir = geyserPluginDir;
        this.networkKey = networkKey;
        this.onMergedPackReady = onMergedPackReady;
        this.onPluginUpdateCandidate = onPluginUpdateCandidate;
        this.http = HttpClient.newBuilder()
                .connectTimeout(PER_BACKEND_TIMEOUT)
                .build();

        this.inboxRoot = new File(workingDir, "inbox");
        this.relayInboxRoot = new File(workingDir, "relay-inbox");
        this.mergedDir = new File(workingDir, "merged");
        this.mergedBedrockZip = new File(mergedDir, MERGED_BEDROCK_ZIP_NAME);
        this.mergedMappings = new File(mergedDir, MERGED_MAPPINGS_NAME);
        this.pluginUpdateCandidatesDir = new File(workingDir, "plugin-update-candidates");
        //noinspection ResultOfMethodCallIgnored
        this.inboxRoot.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        this.relayInboxRoot.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        this.mergedDir.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        this.pluginUpdateCandidatesDir.mkdirs();
        cleanupOwnedPartFiles(this.inboxRoot);
        cleanupOwnedPartFiles(this.relayInboxRoot);

        // Pre-load the previous run's merged pack from disk so a current
        // MergedPack is registered with GeyserBinder before the first new poll
        // cycle completes. Without this, there's a startup window where Geyser
        // already sees the previous run's custom-item mappings (pre-deployed at
        // boot) but has no MergedPack to attach to incoming Bedrock sessions —
        // session load runs against a null pack until the polling loop publishes
        // a fresh merge. Same UUID across runs (NETWORK_PACK_UUID is stable), so
        // client cache hits if the bytes match.
        MergedOutputPublication.Snapshot previousPublication =
                MergedOutputPublication.current(mergedDir);
        if (previousPublication != null) {
            try {
                byte[] sha1 = sha1OfFile(previousPublication.pack());
                if (sha1 != null) {
                    this.current = new MergedPack(
                            previousPublication.pack(),
                            hex(sha1), sha1, MergedPack.NETWORK_PACK_UUID);
                    logger.info("NetworkSync: pre-loaded previous merged pack from "
                            + previousPublication.pack().getAbsolutePath() + " ("
                            + previousPublication.pack().length()
                            + " bytes, sha1 " + hex(sha1) + ") — Bedrock sessions before the first new merge will receive this pack.");
                    // Notify GeyserBinder immediately so it has a non-null current
                    // before its event subscription fires. The proxy plugin's
                    // entrypoint calls bedrock.onMergedPackReady from the same
                    // consumer, which sets GeyserBinder.current.
                    onMergedPackReady.accept(this.current);
                }
            } catch (Throwable t) {
                logger.warn("NetworkSync: failed to pre-load the committed previous merged pack at "
                        + previousPublication.pack().getAbsolutePath()
                        + " — Bedrock sessions before the first merge will get no RSPM pack: "
                        + t.getMessage(), t);
            }
        } else if (mergedBedrockZip.exists() || mergedMappings.exists()) {
            logger.warn("NetworkSync: ignoring uncommitted or hash-invalid previous merged output; "
                    + "the next successful merge will replace it.");
        }
    }

    /**
     * Start the polling loop. Returns immediately. First poll happens after a short
     * initial delay so the proxy can finish booting; subsequent polls every
     * {@code intervalMillis}.
     */
    public void start(long initialDelayMillis, long intervalMillis) {
        if (pollTask != null) return;
        stopped = false;
        lifecycleGeneration.incrementAndGet();
        logger.info("NetworkSync starting (poll interval " + intervalMillis + " ms, network-http-offset "
                + networkHttpOffset + " - endpoint announcements preferred, fallback HTTP port = mcPort + offset)");
        pollTask = scheduler.scheduleRepeating(this::pollOnce, initialDelayMillis, intervalMillis);
    }

    /** Cancel the polling loop. Called on proxy shutdown. */
    public void stop() {
        synchronized (pollCompletionMonitor) {
            stopped = true;
            lifecycleGeneration.incrementAndGet();
        }
        ProxySchedulerAdapter.Cancellable task = pollTask;
        if (task != null) {
            task.cancel();
            pollTask = null;
        }
        MagmaguyRspClient rc = relayClient;
        if (rc != null) {
            rc.abortInFlight();
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(7).toNanos();
        synchronized (pollCompletionMonitor) {
            while (pollInProgress.get()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) break;
                try {
                    long millis = Math.max(1L, Math.min(250L, remaining / 1_000_000L));
                    pollCompletionMonitor.wait(millis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (rc != null) {
            try { rc.close(); } catch (Exception ignored) {}
            relayClient = null;
        }
    }

    /** The most recently published merged pack, or {@code null} if none yet. */
    public MergedPack current() {
        return current;
    }

    /**
     * One poll cycle:
     * <ol>
     *   <li>For each backend, GET {@code /bedrock.zip} and {@code /mappings.json}
     *       with {@code If-Modified-Since}, saving any 200 response to
     *       {@code inbox/&lt;backend&gt;/}.</li>
     *   <li>Hash every file currently under inbox/.</li>
     *   <li>If hashes match the previous cycle, increment {@link #stableCount};
     *       otherwise reset it to 0.</li>
     *   <li>Once stable count &ge; {@link #STABLE_CYCLES_REQUIRED} and the stable
     *       hash set differs from the last-merged set, merge.</li>
     * </ol>
     */
    void pollOnce() {
        pollOnce(lifecycleGeneration.get());
    }

    private void pollOnce(long pollGeneration) {
        if (!isActive(pollGeneration)) return;
        if (!pollInProgress.compareAndSet(false, true)) {
            if (!overlapWarningFired) {
                overlapWarningFired = true;
                logger.warn("NetworkSync: previous poll is still running; skipping overlapping poll. "
                        + "If this repeats, a backend fetch or relay fallback is timing out.");
            }
            return;
        }
        try {
            if (!isActive(pollGeneration)) return;
            cleanupOwnedPartFiles(inboxRoot);
            cleanupOwnedPartFiles(relayInboxRoot);
            BackendListResult backendListResult = safeListBackends();
            if (!backendListResult.successful()) return;
            List<BackendListProvider.Backend> backends = backendListResult.backends();
            if (!isActive(pollGeneration)) return;
            if (backends.isEmpty()) {
                stableCount = 0;
                consecutiveEmptyPolls++;
                maybeFireUnreachableWarning(backends);
                // A proxy can briefly report no backends while its own registry is
                // still coming up, so retain the previous pack for a few cycles. A
                // persistently empty configured server list is authoritative though:
                // do not serve a previous topology's pack forever.
                if (consecutiveEmptyPolls >= UNREACHABLE_WARNING_THRESHOLD_CYCLES) {
                    pruneDirectBackendInbox(inboxRoot, Set.of());
                    pruneRelayInbox(relayInboxRoot, List.of());
                    prunePluginUpdateCandidates(pluginUpdateCandidatesDir, Set.of());
                    acceptedPluginUpdateCandidates.clear();
                    executableUpdateAuthRetries.clear();
                    lastFetchOutcomes.clear();
                    clearPublishedPack("the proxy has no configured backends", pollGeneration);
                    lastInboxHashes = new TreeSet<>();
                    lastMergedHashes = new TreeSet<>();
                }
                return;
            }

            Set<String> liveBackendDirectories = new HashSet<>();
            for (BackendListProvider.Backend backend : backends) {
                liveBackendDirectories.add(sanitizeBackendName(backend.name()));
            }
            pruneDirectBackendInbox(inboxRoot, liveBackendDirectories);
            prunePluginUpdateCandidates(
                    pluginUpdateCandidatesDir, liveBackendDirectories);
            acceptedPluginUpdateCandidates.retainAll(liveBackendDirectories);
            executableUpdateAuthRetries.keySet().retainAll(liveBackendDirectories);
            lastFetchOutcomes.keySet().removeIf(key -> {
                int separator = key.indexOf(':');
                String backend = separator < 0 ? key : key.substring(0, separator);
                return !liveBackendDirectories.contains(backend);
            });

            refreshAnnouncedEndpoints(pollGeneration);
            if (!isActive(pollGeneration)) return;

            // Step 1: fetch per backend. Prefer the backend's own endpoint
            // announcement (it knows the HTTP port it actually bound), then
            // fall back to the legacy mcPort + offset guess. If the chosen port
            // doesn't work, the unreachable-warning machinery surfaces the exact URL.
            // We deliberately do NOT crawl ports as a fallback — that's port-scan
            // behavior from the host's perspective and triggers anti-abuse heuristics.
            // The remote-relay path (via the magmaguy.com hoster) is the categorical
            // answer for setups where direct fetching can't work.
            boolean anyDirectFetchFailedHard = false;
            Map<String, String> correlatedHardFailures = new HashMap<>();
            for (BackendListProvider.Backend b : backends) {
                if (!isActive(pollGeneration)) return;
                String sanitized = sanitizeBackendName(b.name());
                ResolvedBackendEndpoint endpoint = resolveBackendHttpEndpoint(
                        b, networkHttpOffset, announcedEndpoints);
                File backendInbox = new File(inboxRoot, sanitized);
                //noinspection ResultOfMethodCallIgnored
                backendInbox.mkdirs();
                if (endpoint.backendId() != null && !endpoint.backendId().isBlank()) {
                    try {
                        if (ensureDirectBackendProvenance(
                                backendInbox, endpoint.backendId())) {
                            lastInboxHashes = new TreeSet<>();
                            lastMergedHashes = null;
                            stableCount = 0;
                            clearPublishedPack(
                                    "an authoritative backend endpoint changed identity",
                                    pollGeneration);
                        }
                    } catch (IOException provenanceFailure) {
                        throw new IllegalStateException(
                                "Could not quarantine a direct backend cache after its endpoint identity changed",
                                provenanceFailure);
                    }
                }
                fetchDirectArtifactSet(b, endpoint, backendInbox, pollGeneration);
                if (onPluginUpdateCandidate != null
                        && networkKey != null && !networkKey.isBlank()
                        && shouldAttemptExecutableUpdate(sanitized, Instant.now())) {
                    File updateCandidate = new File(
                            pluginUpdateCandidatesDir, sanitized + ".jar");
                    String authorization = NetworkAccessToken.authorizationValue(
                            PackHttpServer.EXECUTABLE_UPDATE_TOKEN_DOMAIN, networkKey);
                    boolean downloaded = fetchIfChanged(
                            b, endpoint, PackHttpServer.EXECUTABLE_UPDATE_PATH,
                            updateCandidate, authorization, pollGeneration);
                    if (downloaded) {
                        acceptedPluginUpdateCandidates.remove(sanitized);
                    }
                    FetchOutcome updateOutcome = lastFetchOutcomes.get(
                            sanitized + ":" + PackHttpServer.EXECUTABLE_UPDATE_PATH);
                    boolean reusableCachedCandidate = updateOutcome != null
                            && updateOutcome.kind() == FetchOutcome.Kind.NOT_MODIFIED_304
                            && updateCandidate.isFile();
                    if ((downloaded || reusableCachedCandidate)
                            && !acceptedPluginUpdateCandidates.contains(sanitized)) {
                        try {
                            if (onPluginUpdateCandidate.test(updateCandidate.toPath())) {
                                acceptedPluginUpdateCandidates.add(sanitized);
                            }
                        } catch (Throwable throwable) {
                            logger.warn("Proxy update handler rejected candidate from "
                                    + b.name() + ": " + throwable.getMessage(), throwable);
                        }
                    }
                }
                // Track whether direct fetch is fundamentally failing for any
                // backend this cycle — a hard failure (network unreachable /
                // bad URL parse) is the signal that the relay-fallback path
                // is worth trying. NOT_FOUND_404 alone is not — that means
                // the backend is up but hasn't produced output yet, and the
                // relay won't have anything either in that case.
                FetchOutcome z = lastFetchOutcomes.get(sanitized + ":" + PackHttpServer.BEDROCK_PACK_PATH);
                FetchOutcome m = lastFetchOutcomes.get(sanitized + ":" + PackHttpServer.GEYSER_MAPPINGS_PATH);
                if (isHardFailure(z) || isHardFailure(m)) {
                    anyDirectFetchFailedHard = true;
                    boolean packAuthoritativelyAbsent = z != null
                            && z.kind() == FetchOutcome.Kind.NOT_FOUND_404;
                    if (!packAuthoritativelyAbsent
                            && endpoint.backendId() != null && !endpoint.backendId().isBlank()) {
                        correlatedHardFailures.put(sanitized, endpoint.backendId());
                    }
                }
            }

            // Step 1.5: relay fallback. If direct fetch failed hard for at
            // least one backend AND we have a network key, ask the magmaguy.com
            // hoster for any Bedrock files that BACKENDS uploaded to the relay
            // under this network's namespace. The relay is the bridge for
            // setups where the proxy can't directly reach a backend's HTTP
            // port — typical of shared / managed Minecraft hosting where MC
            // ports are exposed but adjacent ports are firewalled. On
            // dedicated hosts where direct fetch works, this path is never
            // entered and the relay entries (if any) idle for 30 min and
            // TTL-expire on the hoster.
            if (anyDirectFetchFailedHard
                    && !MagmaguyRspClient.isRemoteRelayDisabled()
                    && networkKey != null
                    && !networkKey.isBlank()) {
                RelayFetchResult relayResult = fetchFromRelay(pollGeneration);
                if (relayResult.listSuccessful()) {
                    Map<String, String> committedAuthority = new HashMap<>();
                    for (Map.Entry<String, String> failure : correlatedHardFailures.entrySet()) {
                        String backendId = normalizeBackendId(failure.getValue());
                        if (backendId != null
                                && relayResult.committedBackendIds().contains(backendId)) {
                            committedAuthority.put(failure.getKey(), backendId);
                        }
                    }
                    // Switch away from direct last-good only when reconciliation
                    // left an actually committed relay set for that exact backend.
                    relayAuthorityByDirectDirectory = Map.copyOf(committedAuthority);
                }
            } else {
                // Relay files are fallback inputs, not permanent contributors.
                // Once direct delivery is healthy (or relay use is disabled),
                // retaining an old relay cache can resurrect content from a
                // backend that has shut down or stopped publishing.
                pruneRelayInbox(relayInboxRoot, List.of());
                relayAuthorityByDirectDirectory = Map.of();
            }

            if (!isActive(pollGeneration)) return;

            Map<String, String> effectiveRelayAuthority = new HashMap<>();
            for (Map.Entry<String, String> entry : correlatedHardFailures.entrySet()) {
                if (entry.getValue().equals(relayAuthorityByDirectDirectory.get(entry.getKey()))) {
                    effectiveRelayAuthority.put(entry.getKey(), entry.getValue());
                }
            }
            List<File> effectiveDirectories = effectiveContributionDirectories(
                    backends, effectiveRelayAuthority);
            removeOrphanMappings(effectiveDirectories);

            // Step 2: hash every file in inbox/.
            Set<String> currentHashes = sha1OfEffectiveInbox(effectiveDirectories);

            // Diagnostic: track consecutive empty-inbox cycles so we can warn
            // the operator after a sensible delay if NOTHING is coming through.
            // Reset on first success — operator-visible state goes back to
            // "healthy" and a future failure can fire the warning again.
            if (currentHashes.isEmpty()) {
                consecutiveEmptyPolls++;
                maybeFireUnreachableWarning(backends);
            } else {
                if (consecutiveEmptyPolls > 0 && unreachableWarningFired) {
                    logger.info("NetworkSync: recovered — at least one backend produced content this cycle.");
                }
                consecutiveEmptyPolls = 0;
                unreachableWarningFired = false;
            }

            // Step 3: stability gate.
            if (currentHashes.equals(lastInboxHashes)) {
                stableCount++;
            } else {
                stableCount = 0;
                lastInboxHashes = currentHashes;
            }

            // Step 4: merge once stable, and only if the stable set differs from the
            // last set we already merged. This makes the merge idempotent across
            // long quiet periods — we won't keep re-zipping the same inputs every cycle.
            if (stableCount >= STABLE_CYCLES_REQUIRED
                    && !currentHashes.equals(lastMergedHashes)) {
                if (currentHashes.isEmpty()) {
                    // A healthy backend can authoritatively report that it no longer
                    // has Bedrock output by returning 404. Once every cached input is
                    // gone, stop serving the previous pack and remove its persisted
                    // outputs so the next restart cannot resurrect it.
                    clearPublishedPack("the backend inbox is empty", pollGeneration);
                    lastMergedHashes = currentHashes;
                    return;
                }
                if (triggerMerge(effectiveDirectories, backends.size(), pollGeneration)) {
                    lastMergedHashes = currentHashes;
                }
            }
        } catch (Exception e) {
            logger.warn("Network sync poll failed", e);
        } finally {
            pollInProgress.set(false);
            overlapWarningFired = false;
            synchronized (pollCompletionMonitor) {
                pollCompletionMonitor.notifyAll();
            }
        }
    }

    private boolean isActive(long generation) {
        return !stopped && lifecycleGeneration.get() == generation;
    }

    /**
     * Fire the "no backend reachable" diagnostic warning at most once per
     * stuck period. Surfaces exactly which URLs were attempted and what each
     * one returned (or how it failed) so the operator can see the bug at a
     * glance: typically either velocity.toml lists a host the proxy can't
     * reach, OR the host is reachable but the HTTP-offset port is wrong, OR
     * there's a firewall in between.
     *
     * <p>Latch — gated by {@link #unreachableWarningFired} — so a setup that
     * stays broken doesn't print this every 5 s. The warning is rearmed on
     * recovery (see pollOnce reset of the latch).
     */
    private void maybeFireUnreachableWarning(List<BackendListProvider.Backend> backends) {
        if (unreachableWarningFired) return;
        if (consecutiveEmptyPolls < UNREACHABLE_WARNING_THRESHOLD_CYCLES) return;
        unreachableWarningFired = true;

        logger.warn("=====================================================================");
        logger.warn("⚠ RSPM NetworkSync: " + consecutiveEmptyPolls
                + " consecutive poll cycles produced no merged pack content.");
        if (backends.isEmpty()) {
            logger.warn("⚠ Backend list is EMPTY — the proxy plugin manager reports no");
            logger.warn("⚠ registered servers. Causes: (a) velocity.toml has no [servers]");
            logger.warn("⚠ block populated yet, (b) the BackendListProvider call failed,");
            logger.warn("⚠ (c) the proxy software is rejecting all server registrations.");
            logger.warn("⚠ Check that `/server` lists at least one backend on this proxy.");
        } else {
            logger.warn("⚠ Backends polled this cycle: " + backends.size());
            for (BackendListProvider.Backend b : backends) {
                String key = sanitizeBackendName(b.name());
                FetchOutcome zipOutcome = lastFetchOutcomes.get(key + ":" + PackHttpServer.BEDROCK_PACK_PATH);
                FetchOutcome mapOutcome = lastFetchOutcomes.get(key + ":" + PackHttpServer.GEYSER_MAPPINGS_PATH);
                ResolvedBackendEndpoint endpoint = resolveBackendHttpEndpoint(
                        b, networkHttpOffset, announcedEndpoints);
                logger.warn("⚠   • " + b.name() + " @ " + b.host() + ":" + b.mcPort()
                        + "  → HTTP @ " + endpoint.host() + ":" + endpoint.port()
                        + " (" + endpoint.source() + ")");
                logger.warn("⚠       /bedrock.zip:   " + describe(zipOutcome));
                logger.warn("⚠       /mappings.json: " + describe(mapOutcome));
            }
            logger.warn("⚠ Most common fixes:");
            logger.warn("⚠   • CONNECT_FAILED on every backend → the proxy cannot reach the");
            logger.warn("⚠     announced/fallback HTTP port. Check that the proxy can reach");
            logger.warn("⚠     the backend address from its own network namespace and that a");
            logger.warn("⚠     firewall is not blocking the shown HTTP URL.");
            logger.warn("⚠     Backends announce their exact RSPM HTTP port automatically;");
            logger.warn("⚠     mcPort + " + networkHttpOffset + " is only the no-announcement fallback.");
            logger.warn("⚠   • NOT_FOUND_404 on every backend → backend(s) up but not producing");
            logger.warn("⚠     a Bedrock pack. Run `/rspm status` on each backend; the");
            logger.warn("⚠     'Bedrock Pack' diagnostic block will tell you why.");
        }
        logger.warn("⚠ This warning fires once per stuck period; recovery is logged when");
        logger.warn("⚠ a backend starts producing content.");
        logger.warn("=====================================================================");
    }

    /** Pretty-print a {@link FetchOutcome} for the diagnostic banner. */
    private static String describe(FetchOutcome o) {
        if (o == null) return "(not yet attempted this session)";
        return switch (o.kind()) {
            case OK_200 -> "OK 200 (served)";
            case NOT_MODIFIED_304 -> "304 Not Modified (cached, healthy)";
            case NOT_FOUND_404 -> "404 Not Found — backend hasn't produced this file yet";
            case UNEXPECTED_STATUS -> "HTTP " + o.httpStatus() + " — backend up but wrong status";
            case CONNECT_FAILED -> "Connect failed — " + o.detail();
            case OTHER_ERROR -> "Error — " + o.detail();
        };
    }

    /**
     * Read-only snapshot of NetworkSync's current state, suitable for rendering
     * by {@code /rspm status} on the proxy side. Captured atomically enough for
     * a single status print — the underlying state can still change between
     * field reads, but that's fine for a human-facing diagnostic.
     */
    public Snapshot snapshot() {
        BackendListResult backendListResult = safeListBackends();
        return new Snapshot(
                backendListResult.successful()
                        ? backendListResult.backends()
                        : lastSuccessfulBackends,
                new java.util.HashMap<>(lastFetchOutcomes),
                consecutiveEmptyPolls,
                unreachableWarningFired,
                current,
                mergedBedrockZip.isFile() ? mergedBedrockZip : null,
                mergedMappings.isFile() ? mergedMappings : null,
                networkHttpOffset,
                List.copyOf(announcedEndpoints));
    }

    /**
     * Snapshot of NetworkSync state for diagnostic commands. Immutable except
     * for the {@code FetchOutcome} map which is a defensive copy taken inside
     * {@link #snapshot()}.
     */
    public record Snapshot(
            List<BackendListProvider.Backend> backends,
            java.util.Map<String, FetchOutcome> fetchOutcomes,
            int consecutiveEmptyPolls,
            boolean unreachableWarningFired,
            MergedPack currentMergedPack,
            File mergedBedrockZip,
            File mergedMappings,
            int networkHttpOffset,
            List<MagmaguyRspClient.BedrockEndpoint> announcedEndpoints) {}

    /**
     * Run the file-union merge across every backend's inbox and publish the result.
     * Backends with no zip in their inbox are skipped silently.
     */
    private boolean triggerMerge(List<File> contributionDirectories,
                                 int configuredBackendCount,
                                 long pollGeneration) {
        if (!isActive(pollGeneration)) return false;
        List<File> zips = new ArrayList<>();
        List<File> mappings = new ArrayList<>();
        Set<String> zipHashes = new TreeSet<>();
        Set<String> mappingsHashes = new TreeSet<>();
        for (File backendInbox : contributionDirectories) {
            File zip = new File(backendInbox, INBOX_BEDROCK_ZIP_NAME);
            File map = new File(backendInbox, INBOX_MAPPINGS_NAME);
            addDirectMergeInput(zip, zips, zipHashes);
            if (zip.isFile()) addDirectMergeInput(map, mappings, mappingsHashes);
        }

        if (zips.isEmpty()) {
            clearPublishedPack("no backend has a usable Bedrock ZIP", pollGeneration);
            return false;
        }

        logger.info("NetworkSync: inbox stabilized — merging " + zips.size()
                + " Bedrock zip(s) and " + mappings.size() + " mappings file(s) across "
                + configuredBackendCount + " backend(s).");

        Path pendingDirectory;
        try {
            pendingDirectory = Files.createTempDirectory(
                    mergedDir.toPath(), ".rspm-network-merge-");
        } catch (IOException exception) {
            logger.warn("NetworkSync: could not create transactional merge staging: "
                    + exception.getMessage(), exception);
            return false;
        }
        File pendingZip = pendingDirectory.resolve(MERGED_BEDROCK_ZIP_NAME).toFile();
        File pendingMappings = pendingDirectory.resolve(MERGED_MAPPINGS_NAME).toFile();
        try {
            File mergedZipFile = new BedrockPackMerger(mixerLogger)
                    .merge(zips, pendingZip, MergedPack.NETWORK_PACK_UUID);
            File mergedMappingsFile = new BedrockMappingsMerger(mixerLogger)
                    .merge(mappings, pendingMappings);

            if (!isActive(pollGeneration)) return false;

            if (mergedZipFile == null) {
                logger.warn("NetworkSync: Bedrock pack merge did not produce an archive; "
                        + "the same stable inputs will be retried on the next poll.");
                return false;
            }
            // Entity-only Bedrock packs legitimately have no custom-item mappings.
            // Requiring a mappings sidecar here silently discarded all custom entity
            // content. Conversely, if mapping inputs existed but the merger failed,
            // fail closed and retry instead of pairing a new pack with stale mappings.
            if (!mappings.isEmpty() && mergedMappingsFile == null) {
                logger.warn("NetworkSync: Geyser mapping merge did not produce output; "
                        + "the same stable inputs will be retried on the next poll.");
                return false;
            }

            // Hash staged bytes before publication; the transaction below copies
            // those exact bytes to the stable path or restores every old target.
            byte[] sha1 = sha1OfFile(mergedZipFile);
            if (sha1 == null) {
                logger.warn("NetworkSync: could not hash the staged Bedrock pack; "
                        + "the same stable inputs will be retried.");
                return false;
            }
            String sha1Hex = hex(sha1);

            if (!publishMergedArtifactSet(
                    mergedZipFile.toPath(),
                    mergedMappingsFile == null ? null : mergedMappingsFile.toPath(),
                    pollGeneration)) {
                return false;
            }

            MergedPack pack = new MergedPack(
                    mergedBedrockZip, sha1Hex, sha1, MergedPack.NETWORK_PACK_UUID);
            synchronized (pollCompletionMonitor) {
                if (!isActive(pollGeneration)) return false;
                current = pack;
                onMergedPackReady.accept(pack);
            }
            logger.info("Merged Bedrock pack published at " + mergedBedrockZip.getAbsolutePath()
                    + " (sha1=" + sha1Hex + ").");
            return true;
        } finally {
            try {
                deleteDirectoryTreeUnder(
                        mergedDir.toPath().toAbsolutePath().normalize(),
                        pendingDirectory.toAbsolutePath().normalize());
            } catch (IOException ignored) {
            }
        }
    }

    private static void addDirectMergeInput(File file, List<File> files, Set<String> hashes) {
        if (file == null || !file.isFile()) {
            return;
        }
        String sha1 = sha1HexOfFile(file);
        if (sha1 != null && !hashes.add(sha1)) return;
        files.add(file);
    }

    // ------------------------------------------------------------------
    // Bedrock relay fallback
    // ------------------------------------------------------------------

    private static boolean isHardFailure(FetchOutcome o) {
        if (o == null) return false;
        return o.kind() == FetchOutcome.Kind.CONNECT_FAILED
                || o.kind() == FetchOutcome.Kind.OTHER_ERROR
                || o.kind() == FetchOutcome.Kind.UNEXPECTED_STATUS;
    }

    private void refreshAnnouncedEndpoints(long pollGeneration) {
        if (!isActive(pollGeneration) || MagmaguyRspClient.isRemoteRelayDisabled()) return;
        if (networkKey == null || networkKey.isBlank()) return;
        Instant now = Instant.now();
        if (now.isBefore(nextEndpointListAttemptAt)) return;
        try {
            List<MagmaguyRspClient.BedrockEndpoint> endpoints =
                    relayClient().listBedrockEndpoints(networkKey);
            if (!isActive(pollGeneration)) return;
            announcedEndpoints = List.copyOf(endpoints);
            nextEndpointListAttemptAt = Instant.EPOCH;
            lastFetchOutcomes.put("endpoint-registry:list",
                    new FetchOutcome(FetchOutcome.Kind.OK_200, 200,
                            "endpoint registry returned " + endpoints.size()
                                    + " endpoint" + (endpoints.size() == 1 ? "" : "s"),
                            now));
        } catch (IOException e) {
            if (!isActive(pollGeneration)) return;
            nextEndpointListAttemptAt = now.plus(ENDPOINT_LIST_FAILURE_BACKOFF);
            lastFetchOutcomes.put("endpoint-registry:list",
                    new FetchOutcome(FetchOutcome.Kind.CONNECT_FAILED, 0,
                            e.getClass().getSimpleName()
                                    + (e.getMessage() != null ? ": " + e.getMessage() : ""),
                            now));
        }
    }

    private MagmaguyRspClient relayClient() {
        MagmaguyRspClient rc = relayClient;
        if (rc == null) {
            rc = new MagmaguyRspClient(java.util.logging.Logger.getLogger("RSPM-NetworkSync"),
                    30, 60);
            relayClient = rc;
        }
        return rc;
    }

    /**
     * Consult the magmaguy.com Bedrock relay for entries uploaded under this
     * network's key and pull them into a {@code relay-&lt;backendId&gt;} subdir
     * of the inbox. The downstream merge step picks them up alongside the
     * direct-fetched files — so a single proxy can have some backends
     * delivering via direct HTTP and others via the relay, transparently.
     *
     * <p>Outcomes are recorded under a synthetic key
     * ({@code "relay-&lt;backendId&gt;:&lt;path&gt;"}) so {@code /rspm status} on
     * the proxy shows relay activity distinctly from direct fetches.</p>
     */
    private RelayFetchResult fetchFromRelay(long pollGeneration) {
        if (!isActive(pollGeneration) || MagmaguyRspClient.isRemoteRelayDisabled()) {
            return RelayFetchResult.unavailable();
        }
        if (networkKey == null || networkKey.isBlank()) return RelayFetchResult.unavailable();
        Instant now = Instant.now();
        if (now.isBefore(nextRelayListAttemptAt)) {
            lastFetchOutcomes.put("relay-magmaguy.com:list",
                    new FetchOutcome(FetchOutcome.Kind.OTHER_ERROR, 0,
                            "backing off after previous relay list failure until "
                                    + nextRelayListAttemptAt,
                            now));
            return RelayFetchResult.unavailable();
        }
        MagmaguyRspClient rc = relayClient();
        String networkKeyHash = com.magmaguy.resourcepackmanager.http.NetworkKeyResolver
                .shortHashForRelay(networkKey);
        if (networkKeyHash == null) return RelayFetchResult.unavailable();

        List<MagmaguyRspClient.BedrockRelayEntry> entries;
        try {
            entries = rc.listBedrockRelay(networkKey);
            if (!isActive(pollGeneration)) return RelayFetchResult.unavailable();
            nextRelayListAttemptAt = Instant.EPOCH;
            lastFetchOutcomes.put("relay-magmaguy.com:list",
                    new FetchOutcome(FetchOutcome.Kind.OK_200, 200,
                            "relay list returned " + entries.size() + " entr"
                                    + (entries.size() == 1 ? "y" : "ies"),
                            now));
        } catch (IOException e) {
            if (!isActive(pollGeneration)) return RelayFetchResult.unavailable();
            nextRelayListAttemptAt = now.plus(RELAY_LIST_FAILURE_BACKOFF);
            lastFetchOutcomes.put("relay-magmaguy.com:list",
                    new FetchOutcome(FetchOutcome.Kind.CONNECT_FAILED, 0,
                            e.getClass().getSimpleName()
                                    + (e.getMessage() != null ? ": " + e.getMessage() : ""),
                            now));
            logger.warn("Bedrock relay list failed after at least one direct backend fetch failed: "
                    + e.getMessage() + ". Direct/cached backend content may still be usable; run /rspm status on the proxy "
                    + "to see which backend HTTP URL triggered relay fallback. Retrying relay list after "
                    + RELAY_LIST_FAILURE_BACKOFF.toSeconds() + "s.");
            return RelayFetchResult.unavailable();
        }

        Map<String, RelayListedArtifactSet> artifactSets = relayArtifactSets(entries);
        for (RelayListedArtifactSet artifactSet : artifactSets.values()) {
            if (!isActive(pollGeneration)) return RelayFetchResult.unavailable();
            String safeId = sanitizeBackendName(artifactSet.backendId());
            File relayDir = new File(relayInboxRoot, safeId);
            //noinspection ResultOfMethodCallIgnored
            relayDir.mkdirs();
            if (artifactSet.invalid() || artifactSet.zip() == null) {
                try {
                    withdrawInboxArtifactSet(relayDir);
                } catch (IOException revokeFailure) {
                    throw new IllegalStateException(
                            "Could not revoke an invalid authoritative relay contribution",
                            revokeFailure);
                }
                continue;
            }
            if (relayArtifactSetMatches(relayDir, artifactSet)) {
                lastFetchOutcomes.put("relay-" + safeId + ":set",
                        new FetchOutcome(FetchOutcome.Kind.NOT_MODIFIED_304, 304,
                                "committed relay artifact-set match", java.time.Instant.now()));
                continue;
            }

            Path staging = null;
            try {
                staging = Files.createTempDirectory(
                        relayInboxRoot.toPath(), ".relay-generation-");
                Path stagedZip = staging.resolve(INBOX_BEDROCK_ZIP_NAME);
                MagmaguyRspClient.RelayDownloadResult zipResult = rc.downloadBedrockRelay(
                        networkKeyHash, artifactSet.backendId(), "zip", stagedZip.toFile());
                if (zipResult == MagmaguyRspClient.RelayDownloadResult.NOT_FOUND) {
                    withdrawInboxArtifactSet(relayDir);
                    continue;
                }
                if (!relayEntryMatches(stagedZip.toFile(), artifactSet.zip())) {
                    // The flat relay route may have changed between LIST and
                    // GET. That is an in-progress observation, not authority
                    // to destroy the previous complete local generation.
                    throw new InProgressArtifactSetException(
                            "relay ZIP changed between list and download");
                }

                ArtifactSetManifest manifest;
                try {
                    manifest = readArtifactSetManifest(stagedZip.toFile());
                } catch (InvalidArtifactSetException legacyOrInvalid) {
                    withdrawInboxArtifactSet(relayDir);
                    lastFetchOutcomes.put("relay-" + safeId + ":set",
                            new FetchOutcome(FetchOutcome.Kind.OTHER_ERROR, 0,
                                    "relay artifact set rejected: "
                                            + legacyOrInvalid.getMessage(),
                                    Instant.now()));
                    continue;
                }

                Path stagedMappings = null;
                if (manifest.mappingsPresent()) {
                    if (!relayEntryMatchesManifest(artifactSet.mappings(), manifest)) {
                        throw new InProgressArtifactSetException(
                                "relay mapping index has not converged to the committed ZIP");
                    }
                    stagedMappings = staging.resolve(INBOX_MAPPINGS_NAME);
                    MagmaguyRspClient.RelayDownloadResult mappingsResult = rc.downloadBedrockRelay(
                            networkKeyHash, artifactSet.backendId(),
                            "mappings", stagedMappings.toFile());
                    if (mappingsResult != MagmaguyRspClient.RelayDownloadResult.DOWNLOADED
                            || !relayEntryMatches(stagedMappings.toFile(), artifactSet.mappings())
                            || !mappingMatchesManifest(stagedMappings.toFile(), manifest)) {
                        throw new InProgressArtifactSetException(
                                "relay mappings changed between list and download");
                    }
                }
                if (!isActive(pollGeneration)) return RelayFetchResult.unavailable();
                publishInboxArtifactSet(
                        relayDir,
                        stagedZip.toFile(),
                        stagedMappings == null ? null : stagedMappings.toFile(),
                        manifest,
                        null,
                        null,
                        pollGeneration);
                lastFetchOutcomes.put("relay-" + safeId + ":set",
                        new FetchOutcome(FetchOutcome.Kind.OK_200, 200,
                                "committed relay generation " + manifest.generationId(),
                                java.time.Instant.now()));
            } catch (InProgressArtifactSetException inProgress) {
                lastFetchOutcomes.put("relay-" + safeId + ":set",
                        new FetchOutcome(FetchOutcome.Kind.OTHER_ERROR, 0,
                                "relay generation in progress — " + inProgress.getMessage(),
                                Instant.now()));
            } catch (IOException ioe) {
                // Transient transport/server failures retain the last-good cache.
                lastFetchOutcomes.put("relay-" + safeId + ":set",
                        new FetchOutcome(FetchOutcome.Kind.OTHER_ERROR, 0,
                                "relay download — " + ioe.getClass().getSimpleName()
                                        + ": " + ioe.getMessage(),
                                java.time.Instant.now()));
            } finally {
                if (staging != null) {
                    try {
                        deleteDirectoryTreeUnder(
                                relayInboxRoot.toPath().toAbsolutePath().normalize(),
                                staging.toAbsolutePath().normalize());
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        try {
            pruneRelayInbox(relayInboxRoot, entries);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not remove relay contributions that are no longer present in the authoritative relay index",
                    e);
        }
        Set<String> committedBackendIds = new HashSet<>();
        for (RelayListedArtifactSet artifactSet : artifactSets.values()) {
            File relayDirectory = new File(
                    relayInboxRoot, sanitizeBackendName(artifactSet.backendId()));
            if (isCommittedArtifactSet(relayDirectory)) {
                committedBackendIds.add(artifactSet.backendId());
            }
        }
        return new RelayFetchResult(true, Set.copyOf(committedBackendIds));
    }

    private record RelayFetchResult(
            boolean listSuccessful,
            Set<String> committedBackendIds) {
        private static RelayFetchResult unavailable() {
            return new RelayFetchResult(false, Set.of());
        }
    }

    // ------------------------------------------------------------------
    // HTTP fetch
    // ------------------------------------------------------------------

    /**
     * Fetches the two direct-backend routes into staging, validates their
     * embedded generation contract, and only then replaces the active inbox
     * pair. A cross-request publication race therefore retains the prior
     * complete set instead of exposing mixed-generation bytes to the merger.
     */
    private void fetchDirectArtifactSet(BackendListProvider.Backend backend,
                                        ResolvedBackendEndpoint endpoint,
                                        File backendInbox,
                                        long pollGeneration) {
        Path staging = null;
        try {
            staging = Files.createTempDirectory(
                    backendInbox.toPath(), ".direct-artifact-set-");
            File stableZip = new File(backendInbox, INBOX_BEDROCK_ZIP_NAME);
            File stableMappings = new File(backendInbox, INBOX_MAPPINGS_NAME);
            DirectFetchResult zipFetch = fetchDirectCandidate(
                    backend, endpoint, PackHttpServer.BEDROCK_PACK_PATH,
                    stableZip, staging.resolve(INBOX_BEDROCK_ZIP_NAME).toFile(),
                    pollGeneration);
            if (!isActive(pollGeneration)) return;
            if (zipFetch.state() == DirectFetchState.NOT_FOUND) {
                recordMappingsRouteNotRequired(
                        backend, "pack route authoritatively returned 404");
                withdrawInboxArtifactSet(backendInbox);
                return;
            }
            if (!zipFetch.usable()) return;

            ArtifactSetManifest manifest;
            try {
                manifest = readArtifactSetManifest(zipFetch.candidate());
            } catch (InvalidArtifactSetException invalid) {
                // A successful authoritative ZIP response with no current
                // generation contract is legacy/invalid, not a transport blip.
                recordMappingsRouteNotRequired(
                        backend, "pack generation was rejected before sidecar fetch");
                withdrawInboxArtifactSet(backendInbox);
                lastFetchOutcomes.put(sanitizeBackendName(backend.name()) + ":set",
                        new FetchOutcome(FetchOutcome.Kind.OTHER_ERROR, 0,
                                "direct artifact set rejected: " + invalid.getMessage(),
                                Instant.now()));
                return;
            }

            DirectFetchResult mappingsFetch;
            if (manifest.mappingsPresent()) {
                mappingsFetch = fetchDirectCandidate(
                        backend, endpoint, PackHttpServer.GEYSER_MAPPINGS_PATH,
                        stableMappings, staging.resolve(INBOX_MAPPINGS_NAME).toFile(),
                        pollGeneration);
            } else {
                // The committed ZIP is authoritative that this generation has
                // no sidecar. Do not turn an unused route/network failure into
                // a hard direct-delivery failure and unnecessary relay switch.
                recordMappingsRouteNotRequired(
                        backend, "committed pack manifest declares no mappings");
                mappingsFetch = DirectFetchResult.notFound();
            }
            if (!isActive(pollGeneration)) return;
            File candidateMappings = mappingsFetch.usable()
                    ? mappingsFetch.candidate()
                    : null;
            if (manifest.mappingsPresent()) {
                if (!mappingsFetch.usable()
                        || !mappingMatchesManifest(candidateMappings, manifest)) {
                    // ZIP and mappings are separate GETs. A mismatch is an
                    // expected observation while a new commit is becoming
                    // visible; preserve last-good and retry next poll.
                    return;
                }
            } else {
                candidateMappings = null;
            }

            if (zipFetch.state() == DirectFetchState.NOT_MODIFIED
                    && (manifest.mappingsPresent()
                    ? mappingsFetch.state() == DirectFetchState.NOT_MODIFIED
                    : !stableMappings.isFile())
                    && committedArtifactSetMatches(backendInbox, manifest)) {
                return;
            }
            publishInboxArtifactSet(
                    backendInbox,
                    zipFetch.candidate(),
                    candidateMappings,
                    manifest,
                    zipFetch,
                    mappingsFetch,
                    pollGeneration);
        } catch (IOException failure) {
            logger.warn("NetworkSync: could not stage the direct Bedrock artifact set for "
                    + backend.name() + ": " + failure.getMessage(), failure);
        } finally {
            if (staging != null) {
                try {
                    deleteDirectoryTreeUnder(
                            backendInbox.toPath().toAbsolutePath().normalize(),
                            staging.toAbsolutePath().normalize());
                } catch (IOException ignored) {
                }
            }
        }
    }

    private DirectFetchResult fetchDirectCandidate(
            BackendListProvider.Backend backend,
            ResolvedBackendEndpoint endpoint,
            String path,
            File stable,
            File staged,
            long pollGeneration) {
        String outcomeKey = sanitizeBackendName(backend.name()) + ":" + path;
        Instant now = Instant.now();
        String url = "http://" + endpoint.host() + ":" + endpoint.port() + path;
        try {
            URI backendUri = new URI(
                    "http", null, endpoint.host(), endpoint.port(), path, null, null);
            url = backendUri.toASCIIString();
            HttpRequest.Builder request = HttpRequest.newBuilder(backendUri)
                    .timeout(PER_BACKEND_TIMEOUT)
                    .GET();
            Path stableEtag = etagSidecar(stable.toPath());
            String localEtag = stable.isFile() ? readPersistedEtag(stableEtag) : null;
            boolean conditional = stable.isFile() && localEtag != null;
            if (conditional) {
                request.header("If-None-Match", localEtag);
                request.header("If-Modified-Since", HTTP_DATE.format(
                        Instant.ofEpochSecond(stable.lastModified() / 1000L)));
            }
            Files.createDirectories(staged.toPath().toAbsolutePath().getParent());
            HttpResponse<Path> response;
            try {
                response = http.send(request.build(), HttpResponse.BodyHandlers.ofFile(
                        staged.toPath(), StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                lastFetchOutcomes.put(outcomeKey,
                        new FetchOutcome(FetchOutcome.Kind.OTHER_ERROR, 0,
                                "interrupted at " + url, now));
                return DirectFetchResult.failed();
            }
            if (!isActive(pollGeneration)) return DirectFetchResult.failed();

            int status = response.statusCode();
            if (status == 304) {
                Files.deleteIfExists(staged.toPath());
                if (!conditional || !stable.isFile()) {
                    Files.deleteIfExists(stableEtag);
                    lastFetchOutcomes.put(outcomeKey,
                            new FetchOutcome(FetchOutcome.Kind.UNEXPECTED_STATUS, 304,
                                    url + " returned 304 without a valid conditional cache", now));
                    return DirectFetchResult.failed();
                }
                lastFetchOutcomes.put(outcomeKey,
                        new FetchOutcome(FetchOutcome.Kind.NOT_MODIFIED_304, 304, url, now));
                return new DirectFetchResult(
                        DirectFetchState.NOT_MODIFIED, stable, null, null);
            }
            if (status == 404) {
                Files.deleteIfExists(staged.toPath());
                lastFetchOutcomes.put(outcomeKey,
                        new FetchOutcome(FetchOutcome.Kind.NOT_FOUND_404, 404, url, now));
                return new DirectFetchResult(
                        DirectFetchState.NOT_FOUND, null, null, null);
            }
            if (status != 200) {
                Files.deleteIfExists(staged.toPath());
                lastFetchOutcomes.put(outcomeKey,
                        new FetchOutcome(FetchOutcome.Kind.UNEXPECTED_STATUS, status, url, now));
                return DirectFetchResult.failed();
            }

            String responseEtag = response.headers().firstValue("ETag")
                    .map(NetworkSync::normalizeEtag)
                    .orElse(LEGACY_NO_ETAG_SENTINEL);
            Instant responseModified = responseLastModified(response).orElse(null);
            lastFetchOutcomes.put(outcomeKey,
                    new FetchOutcome(FetchOutcome.Kind.OK_200, 200, url, now));
            return new DirectFetchResult(
                    DirectFetchState.DOWNLOADED, staged, responseEtag, responseModified);
        } catch (IOException failure) {
            if (isActive(pollGeneration)) {
                lastFetchOutcomes.put(outcomeKey,
                        new FetchOutcome(FetchOutcome.Kind.CONNECT_FAILED, 0,
                                url + " — " + failure.getClass().getSimpleName()
                                        + (failure.getMessage() == null
                                        ? "" : ": " + failure.getMessage()),
                                now));
            }
            return DirectFetchResult.failed();
        } catch (Throwable failure) {
            if (isActive(pollGeneration)) {
                lastFetchOutcomes.put(outcomeKey,
                        new FetchOutcome(FetchOutcome.Kind.OTHER_ERROR, 0,
                                url + " — " + failure.getClass().getSimpleName(), now));
            }
            return DirectFetchResult.failed();
        }
    }

    private enum DirectFetchState {
        DOWNLOADED,
        NOT_MODIFIED,
        NOT_FOUND,
        FAILED
    }

    private record DirectFetchResult(DirectFetchState state, File candidate,
                                     String etag, Instant lastModified) {
        private static DirectFetchResult failed() {
            return new DirectFetchResult(DirectFetchState.FAILED, null, null, null);
        }

        private static DirectFetchResult notFound() {
            return new DirectFetchResult(
                    DirectFetchState.NOT_FOUND, null, null, null);
        }

        private boolean usable() {
            return (state == DirectFetchState.DOWNLOADED
                    || state == DirectFetchState.NOT_MODIFIED)
                    && candidate != null && candidate.isFile();
        }
    }

    private void recordMappingsRouteNotRequired(
            BackendListProvider.Backend backend,
            String detail) {
        lastFetchOutcomes.put(
                sanitizeBackendName(backend.name())
                        + ":" + PackHttpServer.GEYSER_MAPPINGS_PATH,
                new FetchOutcome(
                        FetchOutcome.Kind.NOT_FOUND_404,
                        404,
                        "not requested — " + detail,
                        Instant.now()));
    }

    /**
     * GET {@code http://<backend>:<resolvedHttpPort><path>}; if the response is
     * 200, write the body and its ETag sidecar atomically. If 304, do nothing.
     * Existing pre-ETag cache entries deliberately make one unconditional request;
     * after that, legacy servers use the persisted sentinel plus IMS while current
     * servers use their strong ETag. Any other status or transport error is logged
     * and swallowed. The protected executable-update route applies bounded retry
     * backoff after HTTP 401; other failures are tried again on the next poll.
     */
    private boolean fetchIfChanged(BackendListProvider.Backend b,
                                   ResolvedBackendEndpoint endpoint,
                                   String path,
                                   File dest,
                                   String authorization,
                                   long pollGeneration) {
        if (!isActive(pollGeneration)) return false;
        String url = "http://" + endpoint.host() + ":" + endpoint.port() + path;
        String outcomeKey = sanitizeBackendName(b.name()) + ":" + path;
        java.time.Instant now = java.time.Instant.now();
        Path destination = dest.toPath();
        Path etagSidecar = etagSidecar(destination);
        Path tmp = null;
        Path etagTmp = null;
        try {
            URI backendUri = new URI(
                    "http", null, endpoint.host(), endpoint.port(), path, null, null);
            url = backendUri.toASCIIString();
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(backendUri)
                    .timeout(PER_BACKEND_TIMEOUT)
                    .GET();
            if (authorization != null) {
                reqBuilder.header("Authorization", authorization);
            }
            boolean hasCachedArtifact = dest.isFile();
            String localEtag = hasCachedArtifact ? readPersistedEtag(etagSidecar) : null;
            boolean conditionalRequest = hasCachedArtifact && localEtag != null;
            if (conditionalRequest) {
                reqBuilder.header("If-None-Match", localEtag);
                long lastSec = dest.lastModified() / 1000L;
                reqBuilder.header("If-Modified-Since",
                        HTTP_DATE.format(Instant.ofEpochSecond(lastSec)));
            }

            File parent = dest.getParentFile();
            Path parentPath = parent == null
                    ? Path.of(".").toAbsolutePath().normalize()
                    : parent.toPath().toAbsolutePath().normalize();
            Files.createDirectories(parentPath);

            tmp = Files.createTempFile(parentPath, "." + dest.getName() + ".", ".part");
            HttpResponse<Path> resp;
            try {
                resp = http.send(reqBuilder.build(),
                        HttpResponse.BodyHandlers.ofFile(
                                tmp,
                                java.nio.file.StandardOpenOption.WRITE,
                                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                clearExecutableUpdateAuthRetry(path, outcomeKey);
                lastFetchOutcomes.put(outcomeKey,
                        new FetchOutcome(FetchOutcome.Kind.OTHER_ERROR, 0, "interrupted at " + url, now));
                return false;
            }

            if (!isActive(pollGeneration)) return false;

            int status = resp.statusCode();
            if (status == 304) {
                clearExecutableUpdateAuthRetry(path, outcomeKey);
                if (!conditionalRequest || !dest.isFile()) {
                    Files.deleteIfExists(etagSidecar);
                    lastFetchOutcomes.put(outcomeKey,
                            new FetchOutcome(FetchOutcome.Kind.UNEXPECTED_STATUS, 304,
                                    url + " returned 304 without a valid conditional cache", now));
                    return false;
                }
                lastFetchOutcomes.put(outcomeKey,
                        new FetchOutcome(FetchOutcome.Kind.NOT_MODIFIED_304, 304, url, now));
                return false;
            }
            if (status == 404) {
                clearExecutableUpdateAuthRetry(path, outcomeKey);
                // A reachable backend's 404 is authoritative: it has no current
                // output. Transport failures retain last-good files, but retaining
                // a file across 404 made removed models live forever.
                Files.deleteIfExists(destination);
                Files.deleteIfExists(etagSidecar);
                lastFetchOutcomes.put(outcomeKey,
                        new FetchOutcome(FetchOutcome.Kind.NOT_FOUND_404, 404, url, now));
                return false;
            }
            if (status != 200) {
                if (PackHttpServer.EXECUTABLE_UPDATE_PATH.equals(path) && status == 401) {
                    Duration retryDelay = recordExecutableUpdateAuthFailure(
                            sanitizeBackendName(b.name()), now);
                    warnExecutableUpdateAuthFailure(b.name(), url, retryDelay);
                    lastFetchOutcomes.put(outcomeKey,
                            new FetchOutcome(FetchOutcome.Kind.UNEXPECTED_STATUS, status,
                                    url + " — executable update authentication rejected; "
                                            + "direct Bedrock pack and mappings polling continues; "
                                            + "retry backoff " + describeDuration(retryDelay),
                                    now));
                    return false;
                }
                if (PackHttpServer.EXECUTABLE_UPDATE_PATH.equals(path) && status == 503) {
                    // The backend's protected update route fails closed with 503 while the
                    // backend has no network key — which is every fresh install or wipe
                    // until a player first connects through this proxy, because key
                    // provisioning rides plugin messages and those need a player channel.
                    // Reuses the 401 backoff so boot-time polls don't spam warnings that
                    // read like transport failures (a support thread chased exactly that).
                    Duration retryDelay = recordExecutableUpdateAuthFailure(
                            sanitizeBackendName(b.name()), now);
                    logger.info("[RSPM] Backend " + b.name() + " is not linked to this proxy yet ("
                            + url + " answered HTTP 503): it has no network key until a player first"
                            + " connects to it through this proxy. This is normal right after"
                            + " installing or resetting a backend. Plugin-update propagation from it"
                            + " retries in " + describeDuration(retryDelay)
                            + "; pack and mappings polling is unaffected.");
                    lastFetchOutcomes.put(outcomeKey,
                            new FetchOutcome(FetchOutcome.Kind.UNEXPECTED_STATUS, status,
                                    url + " — backend not yet provisioned with a network key; "
                                            + "retry backoff " + describeDuration(retryDelay),
                                    now));
                    return false;
                }
                clearExecutableUpdateAuthRetry(path, outcomeKey);
                logger.warn("Backend " + b.name() + " " + url + " returned HTTP " + status);
                lastFetchOutcomes.put(outcomeKey,
                        new FetchOutcome(FetchOutcome.Kind.UNEXPECTED_STATUS, status, url, now));
                return false;
            }

            clearExecutableUpdateAuthRetry(path, outcomeKey);

            // Remove metadata first: after any crash between these operations the
            // next poll is unconditional, never a false 304 against a new body.
            if (!isActive(pollGeneration)) return false;
            Files.deleteIfExists(etagSidecar);
            moveReplacing(tmp, destination);
            tmp = null;
            responseLastModified(resp).ifPresent(lastModified -> {
                try {
                    Files.setLastModifiedTime(destination, FileTime.from(lastModified));
                } catch (IOException ignored) {
                    // Conditional GET remains correct-but-less-efficient if a
                    // filesystem refuses the remote timestamp.
                }
            });
            String responseEtag = resp.headers().firstValue("ETag")
                    .map(NetworkSync::normalizeEtag)
                    .orElse(null);
            String etagToPersist = responseEtag == null
                    ? LEGACY_NO_ETAG_SENTINEL
                    : responseEtag;
            etagTmp = Files.createTempFile(parentPath, "." + dest.getName() + ".etag.", ".part");
            Files.writeString(etagTmp, etagToPersist + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            if (!isActive(pollGeneration)) return false;
            moveReplacing(etagTmp, etagSidecar);
            etagTmp = null;
            lastFetchOutcomes.put(outcomeKey,
                    new FetchOutcome(FetchOutcome.Kind.OK_200, 200, url, now));
            return true;
        } catch (IOException e) {
            if (!isActive(pollGeneration)) return false;
            clearExecutableUpdateAuthRetry(path, outcomeKey);
            // Connect refused / connection timeout / unknown host — backend is
            // either down, on a different host/port than velocity.toml suggests,
            // or there's a firewall in the way. We deliberately don't log per-
            // backend per-poll (that would spam every 5s), but we DO record the
            // outcome so the diagnostic warning at the cycle level (triggered
            // after UNREACHABLE_WARNING_THRESHOLD_CYCLES) AND /rspm status on
            // the proxy can both surface it.
            lastFetchOutcomes.put(outcomeKey,
                    new FetchOutcome(FetchOutcome.Kind.CONNECT_FAILED, 0,
                            url + " — " + e.getClass().getSimpleName()
                                    + (e.getMessage() != null ? ": " + e.getMessage() : ""),
                            now));
            return false;
        } catch (Throwable t) {
            if (!isActive(pollGeneration)) return false;
            clearExecutableUpdateAuthRetry(path, outcomeKey);
            logger.warn("Unexpected fetch failure for " + url + ": " + t.getMessage());
            lastFetchOutcomes.put(outcomeKey,
                    new FetchOutcome(FetchOutcome.Kind.OTHER_ERROR, 0,
                            url + " — " + t.getClass().getSimpleName(), now));
            return false;
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                }
            }
            if (etagTmp != null) {
                try {
                    Files.deleteIfExists(etagTmp);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private boolean shouldAttemptExecutableUpdate(String sanitizedBackendName, Instant now) {
        ExecutableUpdateAuthRetry retry = executableUpdateAuthRetries.get(sanitizedBackendName);
        return retry == null || !now.isBefore(retry.nextAttemptAt());
    }

    private Duration recordExecutableUpdateAuthFailure(
            String sanitizedBackendName,
            Instant now) {
        ExecutableUpdateAuthRetry previous =
                executableUpdateAuthRetries.get(sanitizedBackendName);
        Duration delay = previous == null
                ? EXECUTABLE_UPDATE_AUTH_INITIAL_BACKOFF
                : doubledAndCapped(previous.delay(), EXECUTABLE_UPDATE_AUTH_MAX_BACKOFF);
        executableUpdateAuthRetries.put(
                sanitizedBackendName,
                new ExecutableUpdateAuthRetry(now.plus(delay), delay));
        return delay;
    }

    private void clearExecutableUpdateAuthRetry(String path, String outcomeKey) {
        if (!PackHttpServer.EXECUTABLE_UPDATE_PATH.equals(path)) return;
        int separator = outcomeKey.indexOf(':');
        String backend = separator < 0 ? outcomeKey : outcomeKey.substring(0, separator);
        executableUpdateAuthRetries.remove(backend);
    }

    private void warnExecutableUpdateAuthFailure(
            String backendName,
            String url,
            Duration retryDelay) {
        String fingerprint = NetworkKeyResolver.shortHashForRelay(networkKey);
        if (fingerprint != null && fingerprint.length() > 12) {
            fingerprint = fingerprint.substring(0, 12);
        }
        logger.warn("Backend " + backendName + " " + url
                + " returned HTTP 401: executable update authentication was rejected.");
        logger.warn("[RSPM] Direct Bedrock pack and mappings polling is separate and continues; "
                + "this response pauses only automatic ResourcePackManager.jar propagation "
                + "from this backend.");
        logger.warn("[RSPM] Run '/rspm status' on this proxy and on backend " + backendName
                + " and compare the Network key fingerprint"
                + (fingerprint == null ? "." : " (proxy: " + fingerprint + ")."));
        logger.warn("[RSPM] The common cause is a missing or different plugins/floodgate/key.pem. "
                + "If fingerprints differ, fully stop both processes and back up the backend key.pem.");
        logger.warn("[RSPM] Then manually copy the proxy's existing plugins/floodgate/key.pem to the "
                + "affected backend. Never post or paste key.pem, and do not change auth settings.");
        logger.warn("[RSPM] If fingerprints match, verify both sides run the same candidate/version and "
                + "this URL reaches the intended backend. Attach both '/rspm status' outputs and this "
                + "401 warning to support if it persists.");
        logger.warn("[RSPM] Restart the backend, then the proxy. RSPM will retry automatically in "
                + describeDuration(retryDelay) + " (backoff caps at "
                + describeDuration(EXECUTABLE_UPDATE_AUTH_MAX_BACKOFF) + ").");
    }

    private static Duration doubledAndCapped(Duration current, Duration maximum) {
        Duration doubled;
        try {
            doubled = current.multipliedBy(2);
        } catch (ArithmeticException overflow) {
            return maximum;
        }
        return doubled.compareTo(maximum) > 0 ? maximum : doubled;
    }

    private static String describeDuration(Duration duration) {
        long seconds = Math.max(1, duration.toSeconds());
        if (seconds % 60 == 0) {
            long minutes = seconds / 60;
            return minutes + (minutes == 1 ? " minute" : " minutes");
        }
        return seconds + (seconds == 1 ? " second" : " seconds");
    }

    private static Path etagSidecar(Path artifact) {
        return artifact.resolveSibling(artifact.getFileName() + ETAG_SIDECAR_SUFFIX);
    }

    private static String readPersistedEtag(Path sidecar) {
        try {
            if (!Files.isRegularFile(sidecar)) return null;
            return normalizeEtag(Files.readString(sidecar, StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // Unreadable metadata is equivalent to an old cache entry: force one
            // unconditional request and replace it on a successful response.
            return null;
        }
    }

    private static String normalizeEtag(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.length() < 2 || value.length() > 512) return null;
        int quoteIndex = 0;
        if (value.regionMatches(true, 0, "W/", 0, 2)) quoteIndex = 2;
        if (value.length() < quoteIndex + 2
                || value.charAt(quoteIndex) != '"'
                || value.charAt(value.length() - 1) != '"') {
            return null;
        }
        for (int index = quoteIndex + 1; index < value.length() - 1; index++) {
            char character = value.charAt(index);
            if (character == '"' || character < 0x21 || character == 0x7f) return null;
        }
        return value;
    }

    private static void moveReplacing(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicFailed) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ------------------------------------------------------------------
    // Helpers (package-private static where useful for unit testing)
    // ------------------------------------------------------------------

    /**
     * Replace any character outside {@code [A-Za-z0-9._-]} with an underscore so a
     * weird Velocity server name (e.g. {@code "lobby/world"}) can't escape its
     * own inbox directory.
     */
    static String sanitizeBackendName(String raw) {
        if (raw == null || raw.isEmpty()) return "_";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    /**
     * Treat a successful relay index response as authoritative for the local
     * dedicated {@code relay-inbox/} cache. Relay entries expire and backends explicitly
     * delete them on shutdown; without this pruning, the proxy kept those files
     * forever and continued merging a backend that no longer contributed.
     *
     * <p>Only RSPM-owned backend-id directories beneath the supplied relay root
     * are removed. Direct backend inboxes are intentionally untouched.</p>
     */
    static void pruneRelayInbox(
            File inboxRoot,
            List<MagmaguyRspClient.BedrockRelayEntry> liveEntries) throws IOException {
        Path root = inboxRoot.toPath().toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) return;

        Set<String> backendsWithAuthoritativeZip = new HashSet<>();
        if (liveEntries != null) {
            for (MagmaguyRspClient.BedrockRelayEntry entry : liveEntries) {
                String backendId = entry == null
                        ? null
                        : normalizeBackendId(entry.backendId());
                if (backendId == null || !"zip".equals(entry.kind())) continue;
                backendsWithAuthoritativeZip.add(sanitizeBackendName(backendId));
            }
        }

        File[] relayDirectories = inboxRoot.listFiles(File::isDirectory);
        if (relayDirectories == null) return;

        for (File relayDirectory : relayDirectories) {
            Path directory = relayDirectory.toPath().toAbsolutePath().normalize();
            if (!directory.startsWith(root) || directory.equals(root)) {
                throw new IOException("Refusing to prune relay directory outside inbox root: " + directory);
            }
            if (!backendsWithAuthoritativeZip.contains(relayDirectory.getName())) {
                deleteDirectoryTreeUnder(root, directory);
            }
        }
    }

    private static Map<String, RelayListedArtifactSet> relayArtifactSets(
            List<MagmaguyRspClient.BedrockRelayEntry> entries) {
        Map<String, RelayArtifactSetBuilder> builders = new LinkedHashMap<>();
        Map<String, String> ownerBySafeId = new HashMap<>();
        if (entries != null) {
            for (MagmaguyRspClient.BedrockRelayEntry entry : entries) {
                if (entry == null) continue;
                String backendId = normalizeBackendId(entry.backendId());
                if (backendId == null) continue;
                RelayArtifactSetBuilder builder = builders.computeIfAbsent(
                        backendId, RelayArtifactSetBuilder::new);
                String safeId = sanitizeBackendName(backendId);
                String existingOwner = ownerBySafeId.putIfAbsent(safeId, backendId);
                if (existingOwner != null && !existingOwner.equals(backendId)) {
                    builder.invalid = true;
                    RelayArtifactSetBuilder existing = builders.get(existingOwner);
                    if (existing != null) existing.invalid = true;
                }
                if (!validRelayEntry(entry)) {
                    builder.invalid = true;
                } else if ("zip".equals(entry.kind())) {
                    if (builder.zip != null) builder.invalid = true;
                    else builder.zip = entry;
                } else if ("mappings".equals(entry.kind())) {
                    if (builder.mappings != null) builder.invalid = true;
                    else builder.mappings = entry;
                }
            }
        }
        Map<String, RelayListedArtifactSet> out = new LinkedHashMap<>();
        for (RelayArtifactSetBuilder builder : builders.values()) {
            out.put(builder.backendId, new RelayListedArtifactSet(
                    builder.backendId,
                    builder.zip,
                    builder.mappings,
                    builder.invalid));
        }
        return out;
    }

    private static boolean validRelayEntry(MagmaguyRspClient.BedrockRelayEntry entry) {
        return entry != null
                && ("zip".equals(entry.kind()) || "mappings".equals(entry.kind()))
                && normalizeSha1(entry.sha1OrNull()) != null
                && entry.sizeBytes() >= 0L;
    }

    private static String normalizeSha1(String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() != 40) return null;
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (!((character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f'))) return null;
        }
        return normalized;
    }

    private static boolean relayEntryMatches(
            File file,
            MagmaguyRspClient.BedrockRelayEntry entry) {
        if (file == null || !file.isFile() || !validRelayEntry(entry)
                || file.length() != entry.sizeBytes()) return false;
        String actual = sha1HexOfFile(file);
        return actual != null && actual.equals(normalizeSha1(entry.sha1OrNull()));
    }

    private static boolean relayEntryMatchesManifest(
            MagmaguyRspClient.BedrockRelayEntry entry,
            ArtifactSetManifest manifest) {
        return manifest != null && manifest.mappingsPresent()
                && validRelayEntry(entry)
                && "mappings".equals(entry.kind())
                && manifest.mappingsSize() == entry.sizeBytes()
                && manifest.mappingsSha1().equals(normalizeSha1(entry.sha1OrNull()));
    }

    private static boolean relayArtifactSetMatches(
            File relayDirectory,
            RelayListedArtifactSet listed) {
        if (relayDirectory == null || listed == null || listed.zip() == null) return false;
        File zip = new File(relayDirectory, INBOX_BEDROCK_ZIP_NAME);
        if (!relayEntryMatches(zip, listed.zip())) return false;
        try {
            ArtifactSetManifest manifest = readArtifactSetManifest(zip);
            if (!committedArtifactSetMatches(relayDirectory, manifest)) return false;
            return !manifest.mappingsPresent()
                    || relayEntryMatchesManifest(listed.mappings(), manifest);
        } catch (InvalidArtifactSetException ignored) {
            return false;
        }
    }

    private static ArtifactSetManifest readArtifactSetManifest(File zipFile)
            throws InvalidArtifactSetException {
        if (zipFile == null || !zipFile.isFile()) {
            throw new InvalidArtifactSetException("ZIP is missing");
        }
        try (ZipFile archive = new ZipFile(zipFile)) {
            ZipEntry manifestEntry = null;
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!ARTIFACT_SET_MANIFEST_NAME.equals(entry.getName())) continue;
                if (manifestEntry != null || entry.isDirectory()) {
                    throw new InvalidArtifactSetException(
                            "artifact-set manifest is duplicated or not a file");
                }
                manifestEntry = entry;
            }
            if (manifestEntry == null) {
                throw new InvalidArtifactSetException(
                        "artifact-set manifest is missing (legacy publication)");
            }
            if (manifestEntry.getSize() > 65_536L) {
                throw new InvalidArtifactSetException("artifact-set manifest is too large");
            }
            byte[] manifestBytes;
            try (var input = archive.getInputStream(manifestEntry)) {
                manifestBytes = input.readNBytes(65_537);
            }
            if (manifestBytes.length > 65_536) {
                throw new InvalidArtifactSetException("artifact-set manifest is too large");
            }
            JsonObject json = JsonParser.parseString(
                    new String(manifestBytes, StandardCharsets.UTF_8)).getAsJsonObject();
            int formatVersion = json.get("formatVersion").getAsInt();
            if (formatVersion != ARTIFACT_SET_MANIFEST_VERSION) {
                throw new InvalidArtifactSetException(
                        "unsupported artifact-set manifest version " + formatVersion);
            }
            String generationId = json.get("generationId").getAsString().trim();
            if (!UUID.fromString(generationId).toString().equals(generationId)) {
                throw new InvalidArtifactSetException("invalid artifact-set generation id");
            }
            boolean mappingsPresent = json.get("mappingsPresent").getAsBoolean();
            String mappingsSha1 = null;
            long mappingsSize = 0L;
            if (mappingsPresent) {
                mappingsSha1 = normalizeSha1(json.get("mappingsSha1").getAsString());
                mappingsSize = json.get("mappingsSize").getAsLong();
                if (mappingsSha1 == null || mappingsSize < 0L) {
                    throw new InvalidArtifactSetException(
                            "invalid mappings metadata in artifact-set manifest");
                }
            }
            return new ArtifactSetManifest(
                    generationId, mappingsPresent, mappingsSha1, mappingsSize);
        } catch (InvalidArtifactSetException invalid) {
            throw invalid;
        } catch (IOException | RuntimeException invalid) {
            throw new InvalidArtifactSetException(
                    "could not read artifact-set manifest: " + invalid.getMessage(), invalid);
        }
    }

    private static boolean mappingMatchesManifest(
            File mappings,
            ArtifactSetManifest manifest) {
        if (manifest == null || !manifest.mappingsPresent()) {
            return mappings == null || !mappings.isFile();
        }
        if (mappings == null || !mappings.isFile()
                || mappings.length() != manifest.mappingsSize()) return false;
        String actual = sha1HexOfFile(mappings);
        return actual != null && actual.equals(manifest.mappingsSha1());
    }

    private static boolean committedArtifactSetMatches(
            File directory,
            ArtifactSetManifest expected) {
        if (directory == null || expected == null) return false;
        File marker = new File(directory, ARTIFACT_GENERATION_FILE_NAME);
        try {
            if (!marker.isFile()
                    || !expected.generationId().equals(
                    Files.readString(marker.toPath(), StandardCharsets.UTF_8).trim())) {
                return false;
            }
            File zip = new File(directory, INBOX_BEDROCK_ZIP_NAME);
            if (!expected.equals(readArtifactSetManifest(zip))) return false;
            File mappings = new File(directory, INBOX_MAPPINGS_NAME);
            return expected.mappingsPresent()
                    ? mappingMatchesManifest(mappings, expected)
                    : !mappings.exists();
        } catch (IOException invalid) {
            return false;
        }
    }

    private static boolean isCommittedArtifactSet(File directory) {
        if (directory == null) return false;
        try {
            return committedArtifactSetMatches(
                    directory,
                    readArtifactSetManifest(new File(directory, INBOX_BEDROCK_ZIP_NAME)));
        } catch (InvalidArtifactSetException invalid) {
            return false;
        }
    }

    private void publishInboxArtifactSet(
            File directory,
            File candidateZip,
            File candidateMappings,
            ArtifactSetManifest manifest,
            DirectFetchResult zipFetch,
            DirectFetchResult mappingsFetch,
            long pollGeneration) throws IOException {
        if (candidateZip == null || !candidateZip.isFile()
                || !manifest.equals(readArtifactSetManifest(candidateZip))
                || !mappingMatchesManifest(candidateMappings, manifest)) {
            throw new IOException("refusing to publish an unverified artifact set");
        }
        Files.createDirectories(directory.toPath());
        Path rollback = Files.createTempDirectory(
                directory.toPath(), ".artifact-set-rollback-");
        Path zip = new File(directory, INBOX_BEDROCK_ZIP_NAME).toPath();
        Path mappings = new File(directory, INBOX_MAPPINGS_NAME).toPath();
        Path marker = new File(directory, ARTIFACT_GENERATION_FILE_NAME).toPath();
        Path zipEtag = etagSidecar(zip);
        Path mappingsEtag = etagSidecar(mappings);
        PublicationBackup zipBackup = null;
        PublicationBackup mappingsBackup = null;
        PublicationBackup markerBackup = null;
        PublicationBackup zipEtagBackup = null;
        PublicationBackup mappingsEtagBackup = null;
        try {
            zipBackup = PublicationBackup.capture(zip, rollback, "zip");
            mappingsBackup = PublicationBackup.capture(mappings, rollback, "mappings");
            markerBackup = PublicationBackup.capture(marker, rollback, "generation");
            zipEtagBackup = PublicationBackup.capture(zipEtag, rollback, "zip-etag");
            mappingsEtagBackup = PublicationBackup.capture(
                    mappingsEtag, rollback, "mappings-etag");
            if (!isActive(pollGeneration)) throw new IOException("poll generation ended");

            // Marker removal makes intermediate stable-path replacements
            // invisible to effectiveContributionDirectories().
            Files.deleteIfExists(marker);
            publishFrom(candidateMappings == null ? null : candidateMappings.toPath(), mappings);
            publishFrom(candidateZip.toPath(), zip);

            if (zipFetch != null && zipFetch.state() == DirectFetchState.DOWNLOADED) {
                writeStringAtomically(zipEtag, zipFetch.etag() + System.lineSeparator());
            }
            if (!manifest.mappingsPresent()) {
                Files.deleteIfExists(mappingsEtag);
            } else if (mappingsFetch != null
                    && mappingsFetch.state() == DirectFetchState.DOWNLOADED) {
                writeStringAtomically(
                        mappingsEtag, mappingsFetch.etag() + System.lineSeparator());
            }
            if (zipFetch != null && zipFetch.lastModified() != null) {
                try {
                    Files.setLastModifiedTime(zip, FileTime.from(zipFetch.lastModified()));
                } catch (IOException ignored) {
                }
            }
            if (mappingsFetch != null && mappingsFetch.lastModified() != null
                    && Files.isRegularFile(mappings)) {
                try {
                    Files.setLastModifiedTime(
                            mappings, FileTime.from(mappingsFetch.lastModified()));
                } catch (IOException ignored) {
                }
            }
            if (!isActive(pollGeneration)) throw new IOException("poll generation ended");
            writeStringAtomically(marker, manifest.generationId() + System.lineSeparator());
        } catch (IOException publicationFailure) {
            try {
                restorePublication(
                        zipBackup, mappingsBackup, zipEtagBackup, mappingsEtagBackup);
                restorePublication(markerBackup);
            } catch (IOException rollbackFailure) {
                publicationFailure.addSuppressed(rollbackFailure);
                Files.deleteIfExists(marker);
            }
            throw publicationFailure;
        } finally {
            try {
                deleteDirectoryTreeUnder(
                        directory.toPath().toAbsolutePath().normalize(),
                        rollback.toAbsolutePath().normalize());
            } catch (IOException ignored) {
            }
        }
    }

    private static void withdrawInboxArtifactSet(File directory) throws IOException {
        if (directory == null) return;
        Path marker = new File(directory, ARTIFACT_GENERATION_FILE_NAME).toPath();
        Path zip = new File(directory, INBOX_BEDROCK_ZIP_NAME).toPath();
        Path mappings = new File(directory, INBOX_MAPPINGS_NAME).toPath();
        Files.deleteIfExists(marker);
        Files.deleteIfExists(zip);
        Files.deleteIfExists(mappings);
        Files.deleteIfExists(etagSidecar(zip));
        Files.deleteIfExists(etagSidecar(mappings));
    }

    private static final class RelayArtifactSetBuilder {
        private final String backendId;
        private MagmaguyRspClient.BedrockRelayEntry zip;
        private MagmaguyRspClient.BedrockRelayEntry mappings;
        private boolean invalid;

        private RelayArtifactSetBuilder(String backendId) {
            this.backendId = backendId;
        }
    }

    private record RelayListedArtifactSet(
            String backendId,
            MagmaguyRspClient.BedrockRelayEntry zip,
            MagmaguyRspClient.BedrockRelayEntry mappings,
            boolean invalid) {
    }

    private record ArtifactSetManifest(
            String generationId,
            boolean mappingsPresent,
            String mappingsSha1,
            long mappingsSize) {
    }

    private static final class InvalidArtifactSetException extends IOException {
        private InvalidArtifactSetException(String message) {
            super(message);
        }

        private InvalidArtifactSetException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class InProgressArtifactSetException extends IOException {
        private InProgressArtifactSetException(String message) {
            super(message);
        }
    }

    /**
     * Removes cached direct-backend inputs for servers no longer present in the
     * proxy's authoritative server list. Relay caches live under a separate
     * root and are managed by {@link #pruneRelayInbox(File, List)}.
     */
    static void pruneDirectBackendInbox(
            File inboxRoot,
            Set<String> liveSanitizedBackendNames) throws IOException {
        Path root = inboxRoot.toPath().toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) return;
        Set<String> live = liveSanitizedBackendNames == null
                ? Set.of()
                : Set.copyOf(liveSanitizedBackendNames);
        File[] directories = inboxRoot.listFiles(File::isDirectory);
        if (directories == null) return;
        for (File directoryFile : directories) {
            if (live.contains(directoryFile.getName())) continue;
            Path directory = directoryFile.toPath().toAbsolutePath().normalize();
            deleteDirectoryTreeUnder(root, directory);
        }
    }

    static void prunePluginUpdateCandidates(
            File candidatesDirectory,
            Set<String> liveSanitizedBackendNames) throws IOException {
        Path root = candidatesDirectory.toPath().toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) return;
        Set<String> live = liveSanitizedBackendNames == null
                ? Set.of()
                : Set.copyOf(liveSanitizedBackendNames);
        File[] candidates = candidatesDirectory.listFiles(file -> file.isFile()
                && (file.getName().endsWith(".jar")
                || file.getName().endsWith(".jar" + ETAG_SIDECAR_SUFFIX)));
        if (candidates == null) return;
        for (File candidate : candidates) {
            String fileName = candidate.getName();
            String suffix = fileName.endsWith(".jar" + ETAG_SIDECAR_SUFFIX)
                    ? ".jar" + ETAG_SIDECAR_SUFFIX
                    : ".jar";
            String backend = fileName.substring(0, fileName.length() - suffix.length());
            if (live.contains(backend)) continue;
            Path path = candidate.toPath().toAbsolutePath().normalize();
            if (!path.getParent().equals(root)) {
                throw new IOException("Refusing to prune update candidate outside its root: " + path);
            }
            Files.deleteIfExists(path);
        }
    }

    /**
     * Persists authoritative endpoint identity beside a direct cache. A changed
     * identity atomically moves the entire predecessor directory out of the
     * active inbox before any conditional request can fall back to its bytes.
     */
    private boolean ensureDirectBackendProvenance(
            File backendInbox,
            String authoritativeBackendId) throws IOException {
        String normalizedId = normalizeBackendId(authoritativeBackendId);
        if (normalizedId == null) return false;
        Path root = inboxRoot.toPath().toAbsolutePath().normalize();
        Path directory = backendInbox.toPath().toAbsolutePath().normalize();
        if (!directory.startsWith(root) || directory.equals(root)) {
            throw new IOException("Refusing to manage direct cache outside inbox root: " + directory);
        }
        Path provenance = directory.resolve(DIRECT_BACKEND_ID_FILE_NAME);
        String prior = null;
        if (Files.isRegularFile(provenance)) {
            prior = normalizeBackendId(Files.readString(provenance, StandardCharsets.UTF_8));
        }
        boolean hasCachedArtifacts = Files.isRegularFile(directory.resolve(INBOX_BEDROCK_ZIP_NAME))
                || Files.isRegularFile(directory.resolve(INBOX_MAPPINGS_NAME));
        boolean changed = prior != null
                ? !prior.equals(normalizedId)
                : hasCachedArtifacts;
        if (changed) {
            Path quarantineRoot = workingDir.toPath().toAbsolutePath().normalize()
                    .resolve("direct-inbox-quarantine");
            Files.createDirectories(quarantineRoot);
            Path quarantine = quarantineRoot.resolve(
                    directory.getFileName() + "-" + UUID.randomUUID());
            try {
                Files.move(directory, quarantine, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveFailed) {
                Files.move(directory, quarantine);
            }
            Files.createDirectories(directory);
            provenance = directory.resolve(DIRECT_BACKEND_ID_FILE_NAME);
        }
        writeStringAtomically(provenance, normalizedId + System.lineSeparator());
        return changed;
    }

    private static String normalizeBackendId(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > 256) return null;
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (!(Character.isLetterOrDigit(character)
                    || character == '-' || character == '_' || character == '.' || character == ':')) {
                return null;
            }
        }
        return normalized;
    }

    private static void writeStringAtomically(Path target, String value) throws IOException {
        Files.createDirectories(target.toAbsolutePath().normalize().getParent());
        Path pending = target.resolveSibling(
                "." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.writeString(pending, value, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            moveReplacing(pending, target);
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    private static void deleteDirectoryTreeUnder(Path root, Path directory) throws IOException {
        if (!directory.startsWith(root) || directory.equals(root)) {
            throw new IOException("Refusing to remove directory outside its root: " + directory);
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    record ResolvedBackendEndpoint(String host, int port, String source, String backendId) {
        ResolvedBackendEndpoint(String host, int port, String source) {
            this(host, port, source, null);
        }
    }

    static ResolvedBackendEndpoint resolveBackendHttpEndpoint(
            BackendListProvider.Backend backend,
            int networkHttpOffset,
            List<MagmaguyRspClient.BedrockEndpoint> endpoints) {
        if (endpoints != null && !endpoints.isEmpty()) {
            List<MagmaguyRspClient.BedrockEndpoint> mcPortMatches = new ArrayList<>();
            for (MagmaguyRspClient.BedrockEndpoint endpoint : endpoints) {
                if (endpoint == null || endpoint.httpPort() < 1 || endpoint.httpPort() > 65535) continue;
                if (endpoint.mcPort() == backend.mcPort()) {
                    mcPortMatches.add(endpoint);
                }
            }

            List<MagmaguyRspClient.BedrockEndpoint> hostMatches = new ArrayList<>();
            String backendHost = normalizeHost(backend.host());
            for (MagmaguyRspClient.BedrockEndpoint endpoint : mcPortMatches) {
                if (hostMatches(backendHost, endpoint.publicHost())
                        || hostMatches(backendHost, endpoint.sourceIp())) {
                    hostMatches.add(endpoint);
                }
            }

            if (hostMatches.size() == 1) {
                MagmaguyRspClient.BedrockEndpoint endpoint = hostMatches.get(0);
                return announcedEndpoint(backend, endpoint, "announced by backend " + endpoint.backendId());
            }
            if (mcPortMatches.size() == 1) {
                MagmaguyRspClient.BedrockEndpoint endpoint = mcPortMatches.get(0);
                return announcedEndpoint(backend, endpoint, "announced by backend "
                        + endpoint.backendId() + " (unique MC port match)");
            }
        }
        return new ResolvedBackendEndpoint(
                backend.host(),
                backend.mcPort() + networkHttpOffset,
                "mcPort + network-http-offset-v2 " + networkHttpOffset,
                null);
    }

    private static ResolvedBackendEndpoint announcedEndpoint(
            BackendListProvider.Backend backend,
            MagmaguyRspClient.BedrockEndpoint endpoint,
            String source) {
        return new ResolvedBackendEndpoint(
                backend.host(), endpoint.httpPort(), source, endpoint.backendId());
    }

    private static boolean hostMatches(String normalizedBackendHost, String candidate) {
        String normalizedCandidate = normalizeHost(candidate);
        return !normalizedBackendHost.isBlank()
                && !normalizedCandidate.isBlank()
                && normalizedBackendHost.equals(normalizedCandidate);
    }

    private static String normalizeHost(String raw) {
        if (raw == null) return "";
        String host = raw.trim().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.startsWith("::ffff:")) {
            host = host.substring("::ffff:".length());
        }
        return host;
    }

    private List<File> effectiveContributionDirectories(
            List<BackendListProvider.Backend> backends,
            Map<String, String> relayAuthority) {
        List<File> directories = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (BackendListProvider.Backend backend : backends) {
            String directName = sanitizeBackendName(backend.name());
            String relayBackendId = relayAuthority.get(directName);
            File directory = relayBackendId == null
                    ? new File(inboxRoot, directName)
                    : new File(relayInboxRoot, sanitizeBackendName(relayBackendId));
            String identity = directory.toPath().toAbsolutePath().normalize().toString();
            if (seen.add(identity) && isCommittedArtifactSet(directory)) {
                directories.add(directory);
            }
        }
        return directories;
    }

    private void removeOrphanMappings(List<File> contributionDirectories) throws IOException {
        for (File directory : contributionDirectories) {
            File zip = new File(directory, INBOX_BEDROCK_ZIP_NAME);
            if (zip.isFile()) continue;
            Path mappings = new File(directory, INBOX_MAPPINGS_NAME).toPath();
            Files.deleteIfExists(mappings);
            Files.deleteIfExists(etagSidecar(mappings));
        }
    }

    /** Hash only effective ZIP/mapping inputs; metadata and crash leftovers never stabilize a merge. */
    private Set<String> sha1OfEffectiveInbox(List<File> contributionDirectories) throws IOException {
        Set<String> out = new TreeSet<>();
        Path root = workingDir.toPath().toAbsolutePath().normalize();
        for (File directory : contributionDirectories) {
            File zip = new File(directory, INBOX_BEDROCK_ZIP_NAME);
            if (!zip.isFile()) continue;
            for (File file : List.of(zip, new File(directory, INBOX_MAPPINGS_NAME))) {
                if (!file.isFile()) continue;
                byte[] sha = sha1OfFile(file);
                if (sha == null) continue;
                Path path = file.toPath().toAbsolutePath().normalize();
                String rel = path.startsWith(root)
                        ? root.relativize(path).toString().replace('\\', '/')
                        : path.toString().replace('\\', '/');
                out.add(rel + "|" + hex(sha));
            }
        }
        return out;
    }

    private void cleanupOwnedPartFiles(File rootDirectory) {
        if (rootDirectory == null || !rootDirectory.isDirectory()) return;
        try (var paths = Files.walk(rootDirectory.toPath())) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(candidate -> candidate.getFileName().toString().endsWith(".part"))
                    .toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException cleanupFailure) {
            logger.warn("NetworkSync: could not clean stale partial downloads under "
                    + rootDirectory.getAbsolutePath() + ": " + cleanupFailure.getMessage());
        }
    }

    private BackendListResult safeListBackends() {
        try {
            List<BackendListProvider.Backend> listed = backendList.listBackends();
            if (listed == null) {
                throw new IllegalStateException("BackendListProvider returned null");
            }
            List<BackendListProvider.Backend> normalized = listed.stream()
                    .filter(java.util.Objects::nonNull)
                    .sorted(Comparator
                            .comparing((BackendListProvider.Backend backend) ->
                                    nullToEmpty(backend.name()))
                            .thenComparing(backend -> nullToEmpty(backend.host()))
                            .thenComparingInt(BackendListProvider.Backend::mcPort))
                    .toList();
            lastSuccessfulBackends = normalized;
            return new BackendListResult(true, normalized);
        } catch (Throwable t) {
            logger.warn("BackendListProvider failed; retaining the last authoritative backend state this cycle.", t);
            return new BackendListResult(false, lastSuccessfulBackends);
        }
    }

    private record BackendListResult(
            boolean successful,
            List<BackendListProvider.Backend> backends) {
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static java.util.Optional<Instant> responseLastModified(HttpResponse<?> response) {
        if (response == null) return java.util.Optional.empty();
        return response.headers().firstValue("Last-Modified").flatMap(value -> {
            try {
                return java.util.Optional.of(Instant.from(HTTP_DATE.parse(value)));
            } catch (RuntimeException ignored) {
                return java.util.Optional.empty();
            }
        });
    }

    private static void publishAtomically(Path source, Path target) throws IOException {
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        ZipUtil.publishAtomically(source, target);
    }

    private boolean publishMergedArtifactSet(Path stagedZip,
                                             Path stagedMappings,
                                             long pollGeneration) {
        Path backupDirectory;
        try {
            backupDirectory = Files.createTempDirectory(
                    mergedDir.toPath(), ".rspm-network-rollback-");
        } catch (IOException exception) {
            logger.warn("NetworkSync: could not create publication rollback staging: "
                    + exception.getMessage(), exception);
            return false;
        }

        Path deployedMappings = geyserPluginDir == null
                ? null
                : new File(new File(geyserPluginDir, "custom_mappings"),
                MERGED_MAPPINGS_NAME).toPath().toAbsolutePath().normalize();
        PublicationBackup zipBackup = null;
        PublicationBackup mappingsBackup = null;
        PublicationBackup deployedBackup = null;
        PublicationBackup markerBackup = null;
        PublicationBackup revocationBackup = null;
        try {
            zipBackup = PublicationBackup.capture(
                    mergedBedrockZip.toPath(), backupDirectory, "pack.zip");
            mappingsBackup = PublicationBackup.capture(
                    mergedMappings.toPath(), backupDirectory, "mappings.json");
            if (deployedMappings != null) {
                deployedBackup = PublicationBackup.capture(
                        deployedMappings, backupDirectory, "deployed-mappings.json");
            }
            markerBackup = PublicationBackup.capture(
                    MergedOutputPublication.markerPath(mergedDir),
                    backupDirectory,
                    "publication-marker");
            revocationBackup = PublicationBackup.capture(
                    MergedOutputPublication.revocationPath(mergedDir),
                    backupDirectory,
                    "publication-revocation");
            if (!isActive(pollGeneration)) return false;

            MergedOutputPublication.beginMutation(mergedDir);
            publishFrom(stagedMappings, mergedMappings.toPath());
            publishFrom(stagedZip, mergedBedrockZip.toPath());
            if (deployedMappings != null) publishFrom(stagedMappings, deployedMappings);

            if (!isActive(pollGeneration)) {
                restoreMergedPublication(
                        markerBackup,
                        zipBackup, mappingsBackup, deployedBackup, revocationBackup);
                return false;
            }
            MergedOutputPublication.commit(mergedDir);
            if (deployedMappings != null) {
                if (stagedMappings == null) {
                    logger.info("Removed stale Geyser mappings because the current network has no custom-item mappings.");
                } else {
                    logger.info("Geyser mappings deployed to " + deployedMappings.getParent()
                            + " — restart the proxy to apply mapping changes (the pack itself is served live).");
                }
            }
            return true;
        } catch (IOException exception) {
            try {
                restoreMergedPublication(
                        markerBackup,
                        zipBackup, mappingsBackup, deployedBackup, revocationBackup);
            } catch (IOException rollbackFailure) {
                exception.addSuppressed(rollbackFailure);
                MergedOutputPublication.revoke(mergedDir);
            }
            logger.warn("NetworkSync: could not publish the complete merged artifact set: "
                    + exception.getMessage(), exception);
            return false;
        } finally {
            try {
                deleteDirectoryTreeUnder(
                        mergedDir.toPath().toAbsolutePath().normalize(),
                        backupDirectory.toAbsolutePath().normalize());
            } catch (IOException ignored) {
            }
        }
    }

    private static void publishFrom(Path sourceOrNull, Path target) throws IOException {
        if (sourceOrNull == null) {
            Files.deleteIfExists(target);
            return;
        }
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path pending = target.resolveSibling(
                "." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.copy(sourceOrNull, pending, StandardCopyOption.REPLACE_EXISTING);
            publishAtomically(pending, target);
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    private static void restorePublication(PublicationBackup... backups) throws IOException {
        IOException failure = null;
        for (PublicationBackup backup : backups) {
            if (backup == null) continue;
            try {
                if (backup.existed()) {
                    publishFrom(backup.copy(), backup.target());
                } else {
                    Files.deleteIfExists(backup.target());
                }
            } catch (IOException restoreFailure) {
                if (failure == null) failure = restoreFailure;
                else failure.addSuppressed(restoreFailure);
            }
        }
        if (failure != null) throw failure;
    }

    private static void restoreMergedPublication(
            PublicationBackup markerBackup,
            PublicationBackup... dataAndRevocationBackups) throws IOException {
        // Restore bytes and the previous guard state first; the old authority
        // marker is restored atomically last so no mixed set becomes current.
        restorePublication(dataAndRevocationBackups);
        restorePublication(markerBackup);
    }

    private record PublicationBackup(Path target, boolean existed, Path copy) {
        private static PublicationBackup capture(Path target, Path backupDirectory, String name)
                throws IOException {
            boolean existed = Files.exists(target);
            Path copy = backupDirectory.resolve(name);
            if (existed) Files.copy(target, copy, StandardCopyOption.REPLACE_EXISTING);
            return new PublicationBackup(target, existed, copy);
        }
    }

    private void clearPublishedPack(String reason, long pollGeneration) {
        if (!isActive(pollGeneration)) return;
        boolean authorityPersisted = MergedOutputPublication.revoke(mergedDir);
        boolean hadCurrent;
        synchronized (pollCompletionMonitor) {
            if (!isActive(pollGeneration)) return;
            hadCurrent = current != null;
            current = null;
            onMergedPackReady.accept(null);
        }
        try {
            Files.deleteIfExists(mergedBedrockZip.toPath());
            Files.deleteIfExists(mergedMappings.toPath());
            GeyserMappingsDeployer.remove(
                    geyserPluginDir, MERGED_MAPPINGS_NAME, logger);
        } catch (IOException exception) {
            logger.warn("NetworkSync: failed to remove stale published output after "
                    + reason + ": " + exception.getMessage(), exception);
        }
        if (!authorityPersisted) {
            logger.warn("NetworkSync: could not fully persist merged-output withdrawal; "
                    + "stable artifacts were still cleaned best-effort and remain unavailable in this process.");
        }
        if (hadCurrent) {
            logger.info("NetworkSync: stopped publishing the previous Bedrock pack because "
                    + reason + ".");
        }
    }

    private static byte[] sha1OfFile(File f) {
        try (java.io.InputStream in = new java.io.BufferedInputStream(new FileInputStream(f))) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return md.digest();
        } catch (Exception e) {
            return null;
        }
    }

    private static String sha1HexOfFile(File f) {
        byte[] sha1 = sha1OfFile(f);
        return sha1 == null ? null : hex(sha1);
    }

    // LOWERCASE hex is load-bearing here (inbox stability sets, MergedPack ids,
    // relay sha1 comparisons) and deliberately differs from the mixer's
    // Sha1.bytesToHexString, which is UPPERCASE for Bukkit API compatibility.
    // Do not consolidate the two without normalizing every comparison site.
    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

}
