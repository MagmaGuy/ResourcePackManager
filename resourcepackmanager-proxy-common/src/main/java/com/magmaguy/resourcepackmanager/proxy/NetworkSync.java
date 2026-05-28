package com.magmaguy.resourcepackmanager.proxy;

import com.magmaguy.resourcepackmanager.http.MagmaguyRspClient;
import com.magmaguy.resourcepackmanager.http.PackHttpServer;
import com.magmaguy.resourcepackmanager.mixer.bedrock.BedrockMappingsMerger;
import com.magmaguy.resourcepackmanager.mixer.bedrock.BedrockPackMerger;
import com.magmaguy.resourcepackmanager.mixer.engine.MixerLogger;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Proxy-side orchestrator. For each backend in the proxy's server list, pulls
 * {@code /bedrock.zip} and {@code /mappings.json} from the backend's
 * {@link PackHttpServer} (via {@code If-Modified-Since}, so unchanged files
 * cost ~zero bandwidth), waits for the inbox to stabilize across two
 * consecutive poll cycles, and then merges every backend's contribution into a
 * single Bedrock pack + single Geyser mappings JSON.
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

    /** RFC 1123 / HTTP-date formatter for {@code If-Modified-Since}. */
    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
                    .withZone(ZoneOffset.UTC);

    private static final String MERGED_BEDROCK_ZIP_NAME = "Bedrock.zip";
    private static final String MERGED_MAPPINGS_NAME = "rspm_geyser_mappings.json";
    private static final String INBOX_BEDROCK_ZIP_NAME = "Bedrock.zip";
    private static final String INBOX_MAPPINGS_NAME = "mappings.json";

    private final ProxyLogger logger;
    private final ProxySchedulerAdapter scheduler;
    private final BackendListProvider backendList;
    private final File workingDir;
    private final int networkHttpOffset;
    private final MixerLogger mixerLogger;
    private final File geyserPluginDir;
    private final Consumer<MergedPack> onMergedPackReady;
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

    private final File inboxRoot;
    private final File mergedDir;
    private final File mergedBedrockZip;
    private final File mergedMappings;

    private volatile ProxySchedulerAdapter.Cancellable pollTask;
    private volatile MergedPack current;

    /** Set of file SHA-1s under inbox/ at the end of the previous poll cycle. */
    private Set<String> lastInboxHashes = new TreeSet<>();
    /** Number of consecutive poll cycles where inbox hashes matched {@link #lastInboxHashes}. */
    private int stableCount = 0;
    /** Hashes that were last successfully merged. Avoids re-merging identical state. */
    private Set<String> lastMergedHashes = null;

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
            Consumer<MergedPack> onMergedPackReady) {
        this.logger = logger;
        this.scheduler = scheduler;
        this.backendList = backendList;
        this.workingDir = workingDir;
        this.networkHttpOffset = networkHttpOffset;
        this.mixerLogger = mixerLogger;
        this.geyserPluginDir = geyserPluginDir;
        this.networkKey = networkKey;
        this.onMergedPackReady = onMergedPackReady;
        this.http = HttpClient.newBuilder()
                .connectTimeout(PER_BACKEND_TIMEOUT)
                .build();

        this.inboxRoot = new File(workingDir, "inbox");
        this.mergedDir = new File(workingDir, "merged");
        this.mergedBedrockZip = new File(mergedDir, MERGED_BEDROCK_ZIP_NAME);
        this.mergedMappings = new File(mergedDir, MERGED_MAPPINGS_NAME);
        //noinspection ResultOfMethodCallIgnored
        this.inboxRoot.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        this.mergedDir.mkdirs();

        // Pre-load the previous run's merged pack from disk so a current
        // MergedPack is registered with GeyserBinder before the first new poll
        // cycle completes. Without this, there's a startup window where Geyser
        // already sees the previous run's custom-item mappings (pre-deployed at
        // boot) but has no MergedPack to attach to incoming Bedrock sessions —
        // session load runs against a null pack until the polling loop publishes
        // a fresh merge. Same UUID across runs (NETWORK_PACK_UUID is stable), so
        // client cache hits if the bytes match.
        if (mergedBedrockZip.isFile()) {
            try {
                byte[] sha1 = sha1OfFile(mergedBedrockZip);
                if (sha1 != null) {
                    this.current = new MergedPack(mergedBedrockZip, hex(sha1), sha1, MergedPack.NETWORK_PACK_UUID);
                    logger.info("NetworkSync: pre-loaded previous merged pack from "
                            + mergedBedrockZip.getAbsolutePath() + " (" + mergedBedrockZip.length()
                            + " bytes, sha1 " + hex(sha1) + ") — Bedrock sessions before the first new merge will receive this pack.");
                    // Notify GeyserBinder immediately so it has a non-null current
                    // before its event subscription fires. The proxy plugin's
                    // entrypoint calls bedrock.onMergedPackReady from the same
                    // consumer, which sets GeyserBinder.current.
                    onMergedPackReady.accept(this.current);
                }
            } catch (Throwable t) {
                logger.warn("NetworkSync: failed to pre-load previous merged pack at "
                        + mergedBedrockZip.getAbsolutePath() + " — Bedrock sessions before the first merge will get no RSPM pack: " + t.getMessage(), t);
            }
        }
    }

    /**
     * Start the polling loop. Returns immediately. First poll happens after a short
     * initial delay so the proxy can finish booting; subsequent polls every
     * {@code intervalMillis}.
     */
    public void start(long initialDelayMillis, long intervalMillis) {
        if (pollTask != null) return;
        logger.info("NetworkSync starting (poll interval " + intervalMillis + " ms, network-http-offset "
                + networkHttpOffset + " — per-backend HTTP port = mcPort + offset)");
        pollTask = scheduler.scheduleRepeating(this::pollOnce, initialDelayMillis, intervalMillis);
    }

    /** Cancel the polling loop. Called on proxy shutdown. */
    public void stop() {
        ProxySchedulerAdapter.Cancellable task = pollTask;
        if (task != null) {
            task.cancel();
            pollTask = null;
        }
        MagmaguyRspClient rc = relayClient;
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
        try {
            List<BackendListProvider.Backend> backends = safeListBackends();
            if (backends.isEmpty()) {
                // No backends configured. Reset stability so we don't accidentally
                // re-merge an empty inbox into Geyser. Also fire a one-shot warning
                // — operators were spending hours wondering why nothing happens, when
                // the answer was "Velocity hasn't registered any backends yet".
                stableCount = 0;
                consecutiveEmptyPolls++;
                maybeFireUnreachableWarning(backends);
                return;
            }

            // Step 1: fetch per backend using the configured offset. If the offset
            // doesn't work for some backends (typical of shared/managed hosting
            // where the host doesn't expose adjacent ports), the unreachable-warning
            // machinery will fire after a few empty cycles to surface the problem.
            // We deliberately do NOT crawl ports as a fallback — that's port-scan
            // behavior from the host's perspective and triggers anti-abuse heuristics.
            // The remote-relay path (via the magmaguy.com hoster) is the categorical
            // answer for setups where direct fetching can't work.
            boolean anyDirectFetchFailedHard = false;
            for (BackendListProvider.Backend b : backends) {
                String sanitized = sanitizeBackendName(b.name());
                File backendInbox = new File(inboxRoot, sanitized);
                //noinspection ResultOfMethodCallIgnored
                backendInbox.mkdirs();
                fetchIfChanged(b, PackHttpServer.BEDROCK_PACK_PATH,
                        new File(backendInbox, INBOX_BEDROCK_ZIP_NAME));
                fetchIfChanged(b, PackHttpServer.GEYSER_MAPPINGS_PATH,
                        new File(backendInbox, INBOX_MAPPINGS_NAME));
                // Track whether direct fetch is fundamentally failing for any
                // backend this cycle — a hard failure (network unreachable /
                // bad URL parse) is the signal that the relay-fallback path
                // is worth trying. NOT_FOUND_404 alone is not — that means
                // the backend is up but hasn't produced output yet, and the
                // relay won't have anything either in that case.
                FetchOutcome z = lastFetchOutcomes.get(sanitized + ":" + PackHttpServer.BEDROCK_PACK_PATH);
                FetchOutcome m = lastFetchOutcomes.get(sanitized + ":" + PackHttpServer.GEYSER_MAPPINGS_PATH);
                if (isHardFailure(z) || isHardFailure(m)) anyDirectFetchFailedHard = true;
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
            if (anyDirectFetchFailedHard) {
                fetchFromRelay();
            }

            // Step 2: hash every file in inbox/.
            Set<String> currentHashes = sha1OfEveryInboxFile();

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
                    // Inbox is fully empty — no backend has produced output yet.
                    // Don't merge an empty pack into Geyser; just record the state.
                    lastMergedHashes = currentHashes;
                    return;
                }
                triggerMerge(backends);
                lastMergedHashes = currentHashes;
            }
        } catch (Exception e) {
            logger.warn("Network sync poll failed", e);
        }
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
                logger.warn("⚠   • " + b.name() + " @ " + b.host() + ":" + b.mcPort()
                        + "  → HTTP @ " + b.host() + ":" + (b.mcPort() + networkHttpOffset));
                logger.warn("⚠       /bedrock.zip:   " + describe(zipOutcome));
                logger.warn("⚠       /mappings.json: " + describe(mapOutcome));
            }
            logger.warn("⚠ Most common fixes:");
            logger.warn("⚠   • CONNECT_FAILED on every backend → the proxy cannot reach the");
            logger.warn("⚠     HTTP port. Check that velocity.toml has the backend address");
            logger.warn("⚠     the proxy can actually reach (not e.g. a Docker-internal name");
            logger.warn("⚠     that doesn't resolve from the proxy's network), and that");
            logger.warn("⚠     mcPort + " + networkHttpOffset + " (the network-http-offset) is");
            logger.warn("⚠     open between this proxy and the backend host.");
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
        return new Snapshot(
                backendList.listBackends(),
                new java.util.HashMap<>(lastFetchOutcomes),
                consecutiveEmptyPolls,
                unreachableWarningFired,
                current,
                mergedBedrockZip.isFile() ? mergedBedrockZip : null,
                mergedMappings.isFile() ? mergedMappings : null,
                networkHttpOffset);
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
            int networkHttpOffset) {}

    /**
     * Run the file-union merge across every backend's inbox and publish the result.
     * Backends with no zip in their inbox are skipped silently.
     */
    private void triggerMerge(List<BackendListProvider.Backend> backends) {
        List<File> zips = new ArrayList<>();
        List<File> mappings = new ArrayList<>();
        for (BackendListProvider.Backend b : backends) {
            String sanitized = sanitizeBackendName(b.name());
            File backendInbox = new File(inboxRoot, sanitized);
            File zip = new File(backendInbox, INBOX_BEDROCK_ZIP_NAME);
            File map = new File(backendInbox, INBOX_MAPPINGS_NAME);
            if (zip.isFile()) zips.add(zip);
            if (map.isFile()) mappings.add(map);
        }
        // Pull in anything fetched via the relay-fallback path. These live
        // under inbox/relay-<id>/ — sibling dirs to the velocity.toml-keyed
        // ones. Merging them in the same step means relay-delivered content
        // is indistinguishable from direct-delivered content in the final
        // pack, which is the whole point of the bridge.
        File[] relayDirs = inboxRoot.listFiles(f ->
                f.isDirectory() && f.getName().startsWith("relay-"));
        if (relayDirs != null) {
            for (File rd : relayDirs) {
                File zip = new File(rd, INBOX_BEDROCK_ZIP_NAME);
                File map = new File(rd, INBOX_MAPPINGS_NAME);
                if (zip.isFile()) zips.add(zip);
                if (map.isFile()) mappings.add(map);
            }
        }

        if (zips.isEmpty() && mappings.isEmpty()) {
            return;
        }

        logger.info("NetworkSync: inbox stabilized — merging " + zips.size()
                + " Bedrock zip(s) and " + mappings.size() + " mappings file(s) across "
                + backends.size() + " backend(s).");

        File mergedZipFile = new BedrockPackMerger(mixerLogger)
                .merge(zips, mergedBedrockZip, MergedPack.NETWORK_PACK_UUID);
        File mergedMappingsFile = new BedrockMappingsMerger(mixerLogger)
                .merge(mappings, mergedMappings);

        // Either merger returning null means: no real content from any backend
        // this cycle (no convertible items / all inputs empty). User policy is to
        // suppress rather than fall back to a placeholder pack. Clear current and
        // skip the onMergedPackReady callback — new Bedrock sessions will register
        // no RSPM pack at all instead of a 22KB empty one with a prompt.
        if (mergedZipFile == null || mergedMappingsFile == null) {
            current = null;
            logger.info("NetworkSync: no content from any backend this cycle — no merged pack produced."
                    + " Bedrock clients will not receive an RSPM pack until backend(s) have content.");
            return;
        }

        // Deploy mappings into the proxy's Geyser plugin folder so Geyser registers
        // our custom-item identifiers on its next startup. Geyser's
        // GeyserDefineCustomItemsEvent is boot-frozen, so this only takes effect
        // on the NEXT proxy restart — the pack itself is served live below.
        GeyserMappingsDeployer.deploy(geyserPluginDir, mergedMappingsFile, logger);

        // Hash the merged zip so the published MergedPack has a stable identity.
        byte[] sha1 = sha1OfFile(mergedZipFile);
        String sha1Hex = sha1 != null ? hex(sha1) : "";

        MergedPack pack = new MergedPack(mergedZipFile, sha1Hex, sha1, MergedPack.NETWORK_PACK_UUID);
        current = pack;
        onMergedPackReady.accept(pack);
        logger.info("Merged Bedrock pack published at " + mergedZipFile.getAbsolutePath()
                + " (sha1=" + sha1Hex + ").");
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
    private void fetchFromRelay() {
        if (networkKey == null || networkKey.isBlank()) return;
        MagmaguyRspClient rc = relayClient;
        if (rc == null) {
            // 30s socket timeout for list/download — relay fetches are small
            // (a few MB at most for Bedrock zips) and the magmaguy.com endpoint
            // is on a known reliable host.
            rc = new MagmaguyRspClient(java.util.logging.Logger.getLogger("RSPM-NetworkSync"),
                    30, 60);
            relayClient = rc;
        }
        String networkKeyHash = com.magmaguy.resourcepackmanager.http.NetworkKeyResolver
                .shortHashForRelay(networkKey);
        if (networkKeyHash == null) return;

        List<MagmaguyRspClient.BedrockRelayEntry> entries;
        try {
            entries = rc.listBedrockRelay(networkKey);
        } catch (IOException e) {
            logger.warn("Bedrock relay list failed: " + e.getMessage());
            return;
        }

        for (MagmaguyRspClient.BedrockRelayEntry e : entries) {
            String safeId = sanitizeBackendName(e.backendId());
            File relayDir = new File(inboxRoot, "relay-" + safeId);
            //noinspection ResultOfMethodCallIgnored
            relayDir.mkdirs();
            String localName = "zip".equals(e.kind())
                    ? INBOX_BEDROCK_ZIP_NAME
                    : INBOX_MAPPINGS_NAME;
            File dest = new File(relayDir, localName);

            // Skip download if our local copy already matches the relay's sha1.
            // The hoster reports sha1 of the uploaded file; if our last-fetched
            // file hashes the same, nothing changed and we don't burn bandwidth.
            if (dest.isFile() && e.sha1OrNull() != null) {
                byte[] local = sha1OfFile(dest);
                if (local != null && hex(local).equalsIgnoreCase(e.sha1OrNull())) {
                    lastFetchOutcomes.put("relay-" + safeId + ":" + e.kind(),
                            new FetchOutcome(FetchOutcome.Kind.NOT_MODIFIED_304, 304,
                                    "relay sha1 match", java.time.Instant.now()));
                    continue;
                }
            }

            try {
                boolean ok = rc.downloadBedrockRelay(networkKeyHash, e.backendId(), e.kind(), dest);
                if (ok) {
                    lastFetchOutcomes.put("relay-" + safeId + ":" + e.kind(),
                            new FetchOutcome(FetchOutcome.Kind.OK_200, 200,
                                    "via relay (" + e.sizeBytes() + " bytes)",
                                    java.time.Instant.now()));
                } else {
                    lastFetchOutcomes.put("relay-" + safeId + ":" + e.kind(),
                            new FetchOutcome(FetchOutcome.Kind.NOT_FOUND_404, 404,
                                    "relay entry vanished between list and download",
                                    java.time.Instant.now()));
                }
            } catch (IOException ioe) {
                lastFetchOutcomes.put("relay-" + safeId + ":" + e.kind(),
                        new FetchOutcome(FetchOutcome.Kind.OTHER_ERROR, 0,
                                "relay download — " + ioe.getClass().getSimpleName()
                                        + ": " + ioe.getMessage(),
                                java.time.Instant.now()));
            }
        }
    }

    // ------------------------------------------------------------------
    // HTTP fetch
    // ------------------------------------------------------------------

    /**
     * GET {@code http://<backend>:<backendHttpPort><path>}; if the response is
     * 200, write the body to {@code dest} atomically. If 304 (no change since
     * {@code dest.lastModified()}), do nothing. Any other status or transport
     * error is logged and swallowed — the next poll will try again.
     */
    private void fetchIfChanged(BackendListProvider.Backend b, String path, File dest) {
        int httpPort = b.mcPort() + networkHttpOffset;
        String url = "http://" + b.host() + ":" + httpPort + path;
        String outcomeKey = sanitizeBackendName(b.name()) + ":" + path;
        java.time.Instant now = java.time.Instant.now();
        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(PER_BACKEND_TIMEOUT)
                    .GET();
            if (dest.isFile()) {
                long lastSec = dest.lastModified() / 1000L;
                reqBuilder.header("If-Modified-Since",
                        HTTP_DATE.format(Instant.ofEpochSecond(lastSec)));
            }

            File parent = dest.getParentFile();
            if (parent != null) //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();

            File tmp = new File(parent == null ? new File(".") : parent, dest.getName() + ".part");
            HttpResponse<Path> resp;
            try {
                resp = http.send(reqBuilder.build(),
                        HttpResponse.BodyHandlers.ofFile(tmp.toPath()));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                Files.deleteIfExists(tmp.toPath());
                lastFetchOutcomes.put(outcomeKey,
                        new FetchOutcome(FetchOutcome.Kind.OTHER_ERROR, 0, "interrupted at " + url, now));
                return;
            }

            int status = resp.statusCode();
            if (status == 304) {
                // Unchanged; delete the (empty) temp file the body handler created.
                Files.deleteIfExists(tmp.toPath());
                lastFetchOutcomes.put(outcomeKey,
                        new FetchOutcome(FetchOutcome.Kind.NOT_MODIFIED_304, 304, url, now));
                return;
            }
            if (status == 404) {
                // Backend hasn't produced this file yet (no Bedrock conversion run).
                // Common during testbed first boot. Quietly skip — but if we had a
                // previous copy, leave it: removing it would make us re-merge on
                // every poll cycle.
                Files.deleteIfExists(tmp.toPath());
                lastFetchOutcomes.put(outcomeKey,
                        new FetchOutcome(FetchOutcome.Kind.NOT_FOUND_404, 404, url, now));
                return;
            }
            if (status != 200) {
                Files.deleteIfExists(tmp.toPath());
                logger.warn("Backend " + b.name() + " " + url + " returned HTTP " + status);
                lastFetchOutcomes.put(outcomeKey,
                        new FetchOutcome(FetchOutcome.Kind.UNEXPECTED_STATUS, status, url, now));
                return;
            }

            // Atomic rename so a concurrent hash read never sees a half-written file.
            try {
                Files.move(tmp.toPath(), dest.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicFailed) {
                Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            lastFetchOutcomes.put(outcomeKey,
                    new FetchOutcome(FetchOutcome.Kind.OK_200, 200, url, now));
        } catch (IOException e) {
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
        } catch (Throwable t) {
            logger.warn("Unexpected fetch failure for " + url + ": " + t.getMessage());
            lastFetchOutcomes.put(outcomeKey,
                    new FetchOutcome(FetchOutcome.Kind.OTHER_ERROR, 0,
                            url + " — " + t.getClass().getSimpleName(), now));
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
     * SHA-1 every regular file under {@link #inboxRoot} (recursive) and return
     * the (relative-path | hex-hash) strings in a sorted set. Sorting makes the
     * set comparable across poll cycles with simple {@code equals()}.
     */
    private Set<String> sha1OfEveryInboxFile() throws IOException {
        Set<String> out = new TreeSet<>();
        if (!inboxRoot.isDirectory()) return out;
        Path root = inboxRoot.toPath();
        Files.walk(root).filter(Files::isRegularFile).forEach(p -> {
            byte[] sha = sha1OfFile(p.toFile());
            if (sha == null) return;
            String rel = root.relativize(p).toString().replace('\\', '/');
            out.add(rel + "|" + hex(sha));
        });
        return out;
    }

    private List<BackendListProvider.Backend> safeListBackends() {
        try {
            return backendList.listBackends();
        } catch (Throwable t) {
            logger.warn("BackendListProvider threw; treating as empty list this cycle.", t);
            return List.of();
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

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

}
