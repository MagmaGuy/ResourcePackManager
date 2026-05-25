package com.magmaguy.resourcepackmanager.proxy;

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

    public NetworkSync(
            ProxyLogger logger,
            ProxySchedulerAdapter scheduler,
            BackendListProvider backendList,
            File workingDir,
            int networkHttpOffset,
            MixerLogger mixerLogger,
            File geyserPluginDir,
            Consumer<MergedPack> onMergedPackReady) {
        this.logger = logger;
        this.scheduler = scheduler;
        this.backendList = backendList;
        this.workingDir = workingDir;
        this.networkHttpOffset = networkHttpOffset;
        this.mixerLogger = mixerLogger;
        this.geyserPluginDir = geyserPluginDir;
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
                // re-merge an empty inbox into Geyser.
                stableCount = 0;
                return;
            }

            // Step 1: fetch per backend.
            for (BackendListProvider.Backend b : backends) {
                String sanitized = sanitizeBackendName(b.name());
                File backendInbox = new File(inboxRoot, sanitized);
                //noinspection ResultOfMethodCallIgnored
                backendInbox.mkdirs();
                fetchIfChanged(b, PackHttpServer.BEDROCK_PACK_PATH,
                        new File(backendInbox, INBOX_BEDROCK_ZIP_NAME));
                fetchIfChanged(b, PackHttpServer.GEYSER_MAPPINGS_PATH,
                        new File(backendInbox, INBOX_MAPPINGS_NAME));
            }

            // Step 2: hash every file in inbox/.
            Set<String> currentHashes = sha1OfEveryInboxFile();

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
                return;
            }

            int status = resp.statusCode();
            if (status == 304) {
                // Unchanged; delete the (empty) temp file the body handler created.
                Files.deleteIfExists(tmp.toPath());
                return;
            }
            if (status == 404) {
                // Backend hasn't produced this file yet (no Bedrock conversion run).
                // Common during testbed first boot. Quietly skip — but if we had a
                // previous copy, leave it: removing it would make us re-merge on
                // every poll cycle.
                Files.deleteIfExists(tmp.toPath());
                return;
            }
            if (status != 200) {
                Files.deleteIfExists(tmp.toPath());
                logger.warn("Backend " + b.name() + " " + url + " returned HTTP " + status);
                return;
            }

            // Atomic rename so a concurrent hash read never sees a half-written file.
            try {
                Files.move(tmp.toPath(), dest.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicFailed) {
                Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // Connect refused, etc. — backend is down or RPM isn't loaded yet.
            // No log per backend per poll; that would spam.
        } catch (Throwable t) {
            logger.warn("Unexpected fetch failure for " + url + ": " + t.getMessage());
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
