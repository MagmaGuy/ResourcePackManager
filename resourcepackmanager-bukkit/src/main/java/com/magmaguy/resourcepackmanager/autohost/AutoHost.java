package com.magmaguy.resourcepackmanager.autohost;

import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.config.DataConfig;
import com.magmaguy.resourcepackmanager.config.DefaultConfig;
import com.magmaguy.resourcepackmanager.mixer.Mix;
import com.magmaguy.resourcepackmanager.network.NetworkMode;
import com.magmaguy.resourcepackmanager.utils.ServerVersionHelper;
import com.magmaguy.resourcepackmanager.http.BackendIdentity;
import com.magmaguy.resourcepackmanager.http.MagmaguyRspClient;
import com.magmaguy.resourcepackmanager.http.MagmaguyRspClient.UploadResult;
import com.magmaguy.resourcepackmanager.http.PackHttpServer;
import com.magmaguy.resourcepackmanager.http.RspError;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Bukkit-side orchestration for the magmaguy.com auto-hosting flow.
 *
 * <p>This class owns session state ({@link #rspUUID}, {@link #firstUpload},
 * {@link #done}), the keep-alive scheduler, and the Bukkit-specific player
 * notification path. The HTTP request/response plumbing is delegated to a
 * shared {@link MagmaguyRspClient} from {@code resourcepackmanager-http-common}.</p>
 *
 * <p>In network mode this class also runs a small always-on
 * {@link PackHttpServer} that exposes the backend's Bedrock-conversion outputs
 * ({@code /bedrock.zip} and {@code /mappings.json}) for the proxy plugin's
 * {@code NetworkSync} to pull. The pack-zip route (used by Java self-host
 * fallback) 404s until {@link Mix#getFinalResourcePack()} appears; the Bedrock
 * routes 404 until BedrockConversion has produced output.</p>
 */
public class AutoHost {
    // Consistent UUID for identifying ResourcePackManager's pack when using multiple resource packs
    private static final UUID RESOURCE_PACK_UUID = UUID.fromString("a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d");

    // Timeout settings for HTTP requests (in seconds)
    private static final int DEFAULT_SOCKET_TIMEOUT = 60;
    private static final int UPLOAD_SOCKET_TIMEOUT = 300; // 5 minutes for file uploads

    @Getter
    @Setter
    private static boolean done = false;

    private static BukkitTask keepAlive = null;
    @Getter
    private static String rspUUID = null;
    @Setter
    private static boolean firstUpload = true;

    /** Shared HTTP client. Reconstructed on each {@link #initialize()} call. */
    private static volatile MagmaguyRspClient client = null;

    /** Local self-host server when the magmaguy.com upload fails or is force-skipped. */
    private static volatile PackHttpServer selfHostServer = null;
    @Getter
    private static volatile String selfHostedUrl = null;

    /**
     * Bedrock relay upload task. In network mode, this backend pushes its
     * {@code output/ResourcePackManager_Bedrock.zip} and
     * {@code output/rspm_geyser_mappings.json} to the magmaguy.com relay so
     * that a proxy which can't directly reach this backend's HTTP port still
     * has a path to fetch the files. Runs every {@link #RELAY_UPLOAD_PERIOD_MINUTES}
     * to refresh the relay entry's TTL (server-side TTL is 30 min — we upload
     * well before that to be safe). Skipped if the files don't yet exist.
     */
    private static BukkitTask relayUploadTask = null;
    /** Stable per-backend ID. Persisted under plugins/ResourcePackManager/backend-id.txt. */
    private static volatile String backendId = null;
    /**
     * Re-upload cadence for the relay. Hoster TTL is 30 min; we upload every
     * 25 to have a safety margin against scheduling jitter and brief network
     * outages. Pack uploads are bandwidth-cheap (typically a few MB) and the
     * hoster's index update is sha1-checked so identical re-uploads are
     * effectively free.
     */
    private static final long RELAY_UPLOAD_PERIOD_TICKS = 25L * 60L * 20L;

    private AutoHost() {
    }

    public static void sendResourcePack(Player player) {
        String url;
        byte[] hash;
        UUID packUuid = RESOURCE_PACK_UUID;

        // Standalone and network mode both push the backend's OWN pack URL to Java
        // clients. Cross-backend merging is a Bedrock-only feature in this design
        // (proxy mixes + serves merged pack via Geyser); Java clients on multi-
        // backend networks see per-backend packs. See docs/network-mode.md.
        //
        // Not-ready path: pack is still being mixed / uploaded. The player will
        // get the pack automatically once AutoHost finishes (broadcastResourcePackSync
        // re-pushes to every online player on first-success), so they don't need
        // to rejoin — but they need to KNOW that's the situation rather than
        // silently see no pack prompt and assume the server is broken. This
        // warning supersedes the Bedrock-side "pack not ready" modal Geyser-side
        // fires on the proxy: if the BACKEND'S pack isn't built, neither Java nor
        // Bedrock can ever work, so the Java/backend warning is the root-cause
        // signal admins should see first.
        if (!done && selfHostedUrl == null) {
            warnPackNotReady(player);
            return;
        }
        if (selfHostedUrl != null) {
            url = selfHostedUrl;
        } else if (rspUUID != null) {
            url = MagmaguyRspClient.BASE_URL + rspUUID;
        } else {
            return;
        }
        hash = Mix.getFinalSHA1Bytes();

        Logger.info("Sending resource pack to " + player.getName());

        String prompt = DefaultConfig.getResourcePackPrompt();
        boolean force = DefaultConfig.isForceResourcePack();

        if (ServerVersionHelper.supportsMultipleResourcePacks()) {
            // 1.20.3+ supports multiple resource packs - use addResourcePack to coexist with other plugins
            player.addResourcePack(packUuid, url, hash, prompt, force);
        } else {
            // Older versions - use setResourcePack (replaces any existing packs)
            player.setResourcePack(url, hash, prompt, force);
        }
    }

    public static void initialize() {
        if (!DefaultConfig.isAutoHost()) return;
        if (Mix.getFinalResourcePack() == null) return;
        Logger.info("Starting autohost!");
        firstUpload = true;
        done = false;
        rspUUID = null;
        // Reset the public-IP cache so /rspm reload re-detects (admin may have
        // moved the server to a new network between boots / reloads).
        cachedPublicIp = null;
        if (keepAlive != null) keepAlive.cancel();

        // Close any client lingering from a previous initialize() (e.g. /rspm reload).
        if (client != null) {
            try {
                client.close();
            } catch (Exception ignored) {
            }
        }
        client = new MagmaguyRspClient(
                ResourcePackManager.plugin.getLogger(),
                DEFAULT_SOCKET_TIMEOUT,
                UPLOAD_SOCKET_TIMEOUT);

        // In network mode, start the always-on backend HTTP server immediately so the
        // proxy plugin's NetworkSync can pull /bedrock.zip and /mappings.json from
        // the very first poll, even before this backend has produced any output.
        // The routes 404 cleanly until the underlying files appear.
        if (NetworkMode.isActive()) {
            startBackendHttpServerIfNeeded();
            startBedrockRelayUploadTask();
        }

        keepAlive = new BukkitRunnable() {
            int counter = 0;

            @Override
            public void run() {
                // Bail before doing any blocking HTTP work if the plugin is
                // disabling. cancel() alone doesn't interrupt a task that's
                // already mid-execution; without this check, an in-flight
                // upload (10s+ blocking) keeps the task alive past onDisable
                // and Bukkit nags about un-shutdown async tasks.
                if (com.magmaguy.magmacore.MagmaCore.isShutdownRequested(ResourcePackManager.plugin)
                        || isCancelled()) return;

                if (rspUUID != null) {
                    counter = 0;
                    try {
                        sendStillAlive();
                    } catch (Exception e) {
                        rspUUID = null;
                        Logger.warn("Failed to autohost resource pack!");
                        e.printStackTrace();
                    }
                } else {
                    checkFileExistence();
                    if (rspUUID == null && counter % 10 == 0) {
                        Logger.warn("Failed to connect to remote server to autohost the resource pack!");
                    }
                    counter++;
                }
            }
        }.runTaskTimerAsynchronously(ResourcePackManager.plugin, 0, 6 * 60 * 60 * 20L);
    }

    private static void checkFileExistence() {
        // selfHostForce short-circuits everything — straight to self-host, no probe, no remote.
        if (DefaultConfig.isSelfHostForce()) {
            fallbackToSelfHost();
            return;
        }

        // preferSelfHost (new default true): try self-host first, run a HYBRID sanity check
        // (RFC1918 heuristic on resolved host + localhost self-probe of the HTTP server),
        // fall back to remote upload only if either check fails. See DefaultConfig#preferSelfHost
        // javadoc for the limitations of this no-external-probe approach.
        if (DefaultConfig.isPreferSelfHost()
                && DefaultConfig.isSelfHostEnabled()
                && !NetworkMode.isActive()) {
            if (trySelfHostFirst()) {
                return; // Self-host passed both checks — we're done.
            }
            // Either the host looked non-routable, or the local HTTP server didn't
            // respond correctly to a localhost probe. Fall through to remote.
            Logger.info("Self-host sanity checks failed; falling back to remote hosting on magmaguy.com.");
        }

        if (client == null) return;

        Optional<String> initResult;
        try {
            initResult = client.initialize(DataConfig.getRspUUID());
        } catch (IOException e) {
            Logger.warn("Failed to communicate with remote server!");
            e.printStackTrace();
            rspUUID = null;
            fallbackToSelfHost();
            return;
        }

        if (initResult.isEmpty()) {
            rspUUID = null;
            Logger.info("No resource pack found on the server! Uploading resource pack to the server...");
            return;
        }
        rspUUID = initResult.get();
        DataConfig.setRspUUID(rspUUID);

        try {
            MagmaguyRspClient.Sha1Result sha1Result = client.sha1Check(rspUUID, Mix.getFinalSHA1());
            if (sha1Result.matched()) {
                // Remote server already has this resource pack
                Logger.info("Remote server already has this resource pack!");
                done = true;
                sendToOnlinePlayersIfFirstUpload();
            } else if (sha1Result.errorOrNull() != null) {
                // Server returned a structured error during sha1 check — react
                // before wasting a multi-MB upload. SESSION_NOT_FOUND in
                // particular clears rspUUID so the next keep-alive tick
                // reinitializes; uploading against a dead session would just
                // burn bandwidth and fail.
                handleUploadError(sha1Result.errorOrNull());
                fallbackToSelfHost();
            } else {
                uploadFile();
            }
        } catch (IOException e) {
            Logger.warn("Failed to communicate with remote server during SHA1 check!");
            e.printStackTrace();
            fallbackToSelfHost();
        }
    }

    /**
     * Hybrid self-host-first sanity check. Two layers, no external probe:
     *
     * <ol>
     *   <li><b>Heuristic check on resolved host.</b> If {@link #resolveExternalHost()}
     *       lands on an RFC1918, loopback, link-local, or unspecified address
     *       ({@link #isNonRoutableHost(String)}), self-host can't possibly work for
     *       internet clients — skip immediately and let the caller fall through to
     *       remote hosting. Catches the common "ipify lookup failed, fell back to
     *       LAN IP" failure mode.</li>
     *   <li><b>Localhost self-probe.</b> Once the {@link PackHttpServer} is up,
     *       open an HTTP connection to {@code http://127.0.0.1:<port>/rspm.zip}
     *       and verify a 200 with non-empty body. Catches port-bind collisions,
     *       missing pack file, and route-registration bugs — but proves nothing
     *       about external reachability.</li>
     *   <li><b>External reachability probe.</b> POST the announced URL to
     *       {@code POST /rsp/probe} on the magmaguy.com hoster — it fetches the
     *       URL from a public vantage and reports back whether it's reachable.
     *       Catches the most common production failure mode: server has a public
     *       IP but the HTTP port isn't forwarded at the router/firewall, so
     *       Layer 2 passes but no real client can ever download the pack.</li>
     * </ol>
     *
     * <p><b>What this still does NOT detect:</b> the NAT-hairpin edge case where
     * the port IS open to the public internet (Layer 3 passes) but the
     * operator's own router doesn't loop traffic back from inside the LAN.
     * External clients work, but the operator testing from the same machine
     * fails. Per-player URL routing (LAN clients get a LAN URL, internet
     * clients get the public URL) would close that gap — not yet wired.
     * Workaround for now: testing from the host machine with a hairpin-broken
     * router still requires {@code preferSelfHost: false} OR
     * {@code selfHostExternalHost: 127.0.0.1}.</p>
     *
     * @return {@code true} when all checks pass and self-host is now active.
     * {@code false} when the caller should fall through to remote hosting. On
     * {@code false}, the self-host server is torn down so the subsequent
     * remote-upload path doesn't announce a stale URL.
     */
    private static boolean trySelfHostFirst() {
        if (Mix.getFinalResourcePack() == null) return false;

        // Layer 1: heuristic check on resolved external host.
        String host = resolveExternalHost();
        if (host == null || isNonRoutableHost(host)) {
            Logger.info("Self-host external host '" + host + "' is not publicly routable "
                    + "(LAN / loopback / unspecified); skipping self-host attempt.");
            return false;
        }

        // Start the self-host server (or reuse if already up).
        if (!fallbackToSelfHost()) return false;
        if (selfHostedUrl == null) return false;

        // Layer 2: localhost self-probe — confirm the HTTP server is up and the
        // pack route serves a non-empty body. This catches everything between
        // "PackHttpServer.start() returned without throwing" and "an actual
        // client could download the pack" EXCEPT the external-firewall case.
        int port = (selfHostServer != null) ? selfHostServer.port() : -1;
        if (port <= 0 || !localhostSelfProbe(port)) {
            Logger.info("Localhost self-probe of the self-host HTTP server failed; "
                    + "falling back to remote hosting.");
            tearDownSelfHost();
            return false;
        }

        // Layer 3: external reachability probe via magmaguy.com hoster.
        // The localhost probe just proved the server responds on 127.0.0.1; this
        // step proves the URL is reachable FROM THE PUBLIC INTERNET, which is
        // what actually matters for the clients we'll announce it to. Catches
        // the very common "public IP detected, port not forwarded at router"
        // failure mode that the previous two-layer check committed to silently.
        if (!externalReachabilityProbe(selfHostedUrl)) {
            // externalReachabilityProbe logs the specific reason. Tear down so
            // the subsequent remote-upload path can re-bind cleanly.
            tearDownSelfHost();
            return false;
        }

        Logger.info("Self-host sanity checks passed; using self-hosting at " + selfHostedUrl);
        return true;
    }

    /**
     * Layer 3 reachability check: ask the magmaguy.com hoster to fetch
     * {@code selfHostedUrl} from its public vantage. The hoster enforces SSRF
     * guards (rejects RFC1918 / loopback targets) and a tight timeout; we get
     * a structured result back without hand-rolling our own probing
     * infrastructure.
     *
     * <p><b>Decision policy on probe outcomes:</b>
     * <ul>
     *   <li>{@code reachable=true}  → external clients can reach our URL.
     *       Self-host commits. Best path: zero-bandwidth-to-hoster + low latency.</li>
     *   <li>{@code reachable=false} → external clients CAN'T reach our URL.
     *       Tear down self-host so the caller falls through to the
     *       magmaguy.com upload path (which is universally reachable).</li>
     *   <li>Probe communication itself fails (IOException) → we couldn't
     *       verify either way. Default to KEEPING self-host: refusing to
     *       commit just because we can't talk to magmaguy.com would be
     *       paradoxical (the fallback path needs magmaguy.com too). A
     *       legitimately broken self-host setup will be surfaced by clients
     *       failing to download, which is no worse than the pre-probe
     *       behavior.</li>
     * </ul>
     *
     * @return {@code true} if the probe confirmed external reachability OR
     * couldn't be performed. {@code false} only when the hoster explicitly
     * told us the URL is unreachable.
     */
    private static boolean externalReachabilityProbe(String url) {
        MagmaguyRspClient c = client;
        if (c == null) {
            // No client (extremely unlikely — initialize() set it before
            // calling us). Don't block self-host on inability to probe.
            return true;
        }
        try {
            MagmaguyRspClient.ProbeResult result = c.probe(url);
            if (result.reachable()) {
                Logger.info("External reachability probe via magmaguy.com: " + url
                        + " is reachable from the public internet (HTTP "
                        + result.status() + ", " + result.durationMs() + " ms). "
                        + "Committing to self-host.");
                return true;
            }
            Logger.warn("External reachability probe via magmaguy.com: " + url
                    + " is NOT reachable from the public internet (reason: "
                    + result.reasonOrNull() + "). Most likely cause: HTTP port "
                    + "not forwarded at the router / firewalled. Falling back "
                    + "to remote hosting on magmaguy.com so the pack is "
                    + "actually downloadable by clients. To use self-host "
                    + "instead, open the HTTP port at your firewall + router.");
            return false;
        } catch (java.io.IOException e) {
            // We couldn't talk to magmaguy.com to ask. Don't fail-closed —
            // see method javadoc decision policy.
            Logger.info("External reachability probe via magmaguy.com failed to "
                    + "communicate (" + e.getMessage() + "); keeping self-host. "
                    + "If clients can't reach the pack URL, set preferSelfHost: false.");
            return true;
        }
    }

    /**
     * Open an HTTP connection to {@code 127.0.0.1:<port>/rspm.zip}, verify 200
     * status and non-empty body. Times out aggressively (3s) so a slow probe
     * can't drag out boot. Uses {@link java.net.HttpURLConnection} so we don't
     * pull in another Apache HC client dependency for one self-call.
     *
     * <p>Returns {@code false} on any anomaly (connection refused, non-200
     * status, empty body, timeout, IO error) — operators see the underlying
     * cause as part of the warning log line.</p>
     */
    private static boolean localhostSelfProbe(int port) {
        java.net.HttpURLConnection conn = null;
        try {
            java.net.URI uri = new java.net.URI("http", null, "127.0.0.1", port, "/rspm.zip", null, null);
            conn = (java.net.HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            int code = conn.getResponseCode();
            int len = conn.getContentLength();
            if (code != 200) {
                Logger.warn("Localhost self-probe: HTTP " + code + " from http://127.0.0.1:" + port + "/rspm.zip");
                return false;
            }
            if (len == 0) {
                Logger.warn("Localhost self-probe: server returned 200 but Content-Length=0; pack file may be empty.");
                return false;
            }
            return true;
        } catch (java.io.IOException | java.net.URISyntaxException e) {
            Logger.warn("Localhost self-probe failed: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Tear down the self-host server we started speculatively before the
     * hybrid sanity checks failed, so the subsequent remote-upload path
     * doesn't accidentally announce a URL that nobody on the internet can
     * reach.
     *
     * <p>Resets {@link #selfHostedUrl} + {@link #done} so the caller's
     * remote-upload path can re-enter cleanly. The server itself is closed
     * (and its port released) — we'll re-create it on demand if a later
     * upload-failure path calls {@link #fallbackToSelfHost()} again.</p>
     */
    private static void tearDownSelfHost() {
        PackHttpServer server = selfHostServer;
        if (server != null) {
            try {
                server.close();
            } catch (Exception ignored) {
                // expected during teardown
            }
        }
        selfHostServer = null;
        selfHostedUrl = null;
        done = false;
    }

    /**
     * @return true if the host string is RFC1918, loopback, link-local, or
     * the unspecified address. Used to skip the reachability probe entirely
     * when the auto-detected host obviously isn't reachable from outside.
     */
    private static boolean isNonRoutableHost(String host) {
        if (host == null || host.isBlank()) return true;
        String h = host.toLowerCase();
        if (h.equals("localhost") || h.equals("0.0.0.0")) return true;
        if (h.startsWith("127.")) return true;
        if (h.startsWith("10.")) return true;
        if (h.startsWith("192.168.")) return true;
        if (h.startsWith("169.254.")) return true; // link-local
        // 172.16.0.0/12 → 172.16.* through 172.31.*
        if (h.startsWith("172.")) {
            String[] parts = h.split("\\.");
            if (parts.length >= 2) {
                try {
                    int second = Integer.parseInt(parts[1]);
                    if (second >= 16 && second <= 31) return true;
                } catch (NumberFormatException ignored) {}
            }
        }
        return false;
    }

    public static void uploadFile() {
        if (client == null || rspUUID == null) return;
        Logger.info("Uploading resource!");

        UploadResult result;
        try {
            result = client.upload(rspUUID, Mix.getFinalResourcePack());
        } catch (IOException e) {
            Logger.warn("Failed to communicate with remote server during upload!");
            e.printStackTrace();
            fallbackToSelfHost();
            return;
        }

        if (result.success()) {
            Logger.info("Uploaded resource pack for automatic hosting! url: " + result.urlOrNull());
            done = true;
            sendToOnlinePlayersIfFirstUpload();
        } else {
            handleUploadError(result.errorOrNull());
            fallbackToSelfHost();
        }
    }

    private static void sendStillAlive() throws IOException {
        if (client == null || rspUUID == null) return;
        try {
            if (!client.stillAlive(rspUUID)) {
                // Non-2xx — session may have expired. Reset UUID to trigger re-initialization.
                rspUUID = null;
            }
        } catch (IOException e) {
            Logger.warn("Failed to communicate with remote server during still alive ping!");
            throw e;
        }
    }

    public static void dataComplianceRequest() throws IOException {
        if (rspUUID == null) return;

        // Use the shared client if available, otherwise spin up an ad-hoc one
        // (e.g. command issued before initialize() ran). Ad-hoc clients are
        // closed immediately after the call.
        MagmaguyRspClient activeClient = client;
        boolean ownsClient = false;
        if (activeClient == null) {
            activeClient = new MagmaguyRspClient(
                    ResourcePackManager.plugin.getLogger(),
                    DEFAULT_SOCKET_TIMEOUT,
                    UPLOAD_SOCKET_TIMEOUT);
            ownsClient = true;
        }

        try {
            File zipFile = new File(ResourcePackManager.plugin.getDataFolder().getAbsolutePath()
                    + File.separatorChar + "data_compliance" + File.separatorChar + "data.zip");
            if (!zipFile.getParentFile().exists()) zipFile.getParentFile().mkdirs();
            if (zipFile.exists()) zipFile.delete();
            zipFile.createNewFile();

            activeClient.downloadDataCompliance(rspUUID, zipFile);

            InputStream inputStream = ResourcePackManager.plugin.getResource("ReadMe.md");
            File readMe = new File(ResourcePackManager.plugin.getDataFolder().getAbsolutePath()
                    + File.separatorChar + "data_compliance" + File.separatorChar + "ReadMe.md");
            if (!readMe.exists()) readMe.createNewFile();
            // Copy the InputStream to the file
            if (inputStream != null) {
                Files.copy(inputStream, readMe.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            Logger.warn("Failed to communicate with remote server!");
            e.printStackTrace();
        } finally {
            if (ownsClient) {
                try {
                    activeClient.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    public static void shutdown() {
        if (keepAlive != null) keepAlive.cancel();
        if (relayUploadTask != null) {
            relayUploadTask.cancel();
            relayUploadTask = null;
        }
        // Best-effort: drop this backend's relay entry on clean shutdown so
        // the proxy stops seeing it immediately rather than waiting up to
        // 30 min for TTL. We DO NOT block shutdown on this — fire and forget
        // on the existing scheduler if a client is available. If it fails,
        // the hoster's TTL sweep will catch up.
        if (NetworkMode.isActive() && backendId != null) {
            MagmaguyRspClient deleteClient = client;
            if (deleteClient != null) {
                try {
                    String networkKey = NetworkMode.getNetworkKey();
                    if (networkKey != null && !networkKey.isBlank()) {
                        deleteClient.deleteBedrockRelay(networkKey, backendId, null);
                    }
                } catch (Exception ignored) {
                    // Fire-and-forget; TTL covers us.
                }
            }
        }
        // Abort any in-flight HTTP request (initialize / sha1 / upload). Without
        // this, a multi-MB upload can keep the async task alive past onDisable
        // and Bukkit nags about un-shutdown async tasks.
        MagmaguyRspClient c = client;
        if (c != null) {
            c.abortInFlight();
            try {
                c.close();
            } catch (Exception ignored) {
            }
            client = null;
        }
        if (selfHostServer != null) {
            try {
                selfHostServer.close();
            } catch (Exception ignored) {
            }
            selfHostServer = null;
            selfHostedUrl = null;
        }
        done = false;
        rspUUID = null;
    }

    /**
     * Start the recurring Bedrock relay upload in network mode. Runs immediately
     * (so the first proxy poll has something to fetch) and then every
     * {@link #RELAY_UPLOAD_PERIOD_TICKS} ticks. Skipped silently if the Bedrock
     * conversion output files aren't on disk yet — a later tick will succeed
     * once {@code BedrockConversion.generate} has run.
     *
     * <p>The relay is a bridge for setups where the proxy can't directly reach
     * this backend's HTTP port (typical of shared / managed Minecraft hosting
     * where the MC port is exposed but adjacent ports are firewalled). On a
     * dedicated host where direct fetch works, the relay entries are simply
     * never used by the proxy — they expire on the hoster after 30 min idle
     * and the only cost is the periodic upload of a small Bedrock zip.</p>
     */
    private static void startBedrockRelayUploadTask() {
        if (relayUploadTask != null) return;
        if (backendId == null) {
            backendId = BackendIdentity.loadOrCreate(
                    ResourcePackManager.plugin.getDataFolder().toPath().resolve("backend-id.txt"));
            Logger.info("RSPM backend-id for Bedrock relay: " + backendId);
        }
        relayUploadTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (com.magmaguy.magmacore.MagmaCore.isShutdownRequested(ResourcePackManager.plugin)
                        || isCancelled()) return;
                pushBedrockRelayOnce();
            }
        }.runTaskTimerAsynchronously(ResourcePackManager.plugin, 200L, RELAY_UPLOAD_PERIOD_TICKS);
    }

    /**
     * One-shot push of bedrock.zip + mappings.json to the relay. Called by the
     * recurring relay task and (TODO) on demand after a fresh BedrockConversion.
     */
    private static void pushBedrockRelayOnce() {
        MagmaguyRspClient relayClient = client;
        if (relayClient == null) return;
        if (backendId == null) return;

        String networkKey = NetworkMode.getNetworkKey();
        if (networkKey == null || networkKey.isBlank()) {
            // Without a network key the proxy has no namespace to find us under
            // — nothing to upload. The proxy will hit the same condition and
            // skip the relay entirely.
            return;
        }

        File outputDir = new File(ResourcePackManager.plugin.getDataFolder(), "output");
        File bedrockZip = new File(outputDir, "ResourcePackManager_Bedrock.zip");
        File geyserMappings = new File(outputDir, "rspm_geyser_mappings.json");

        int uploaded = 0;
        if (bedrockZip.isFile()) {
            try {
                relayClient.uploadBedrockRelay(networkKey, backendId, "zip", bedrockZip, null);
                uploaded++;
            } catch (IOException e) {
                Logger.warn("Bedrock relay upload (zip) failed: " + e.getMessage());
            }
        }
        if (geyserMappings.isFile()) {
            try {
                relayClient.uploadBedrockRelay(networkKey, backendId, "mappings", geyserMappings, null);
                uploaded++;
            } catch (IOException e) {
                Logger.warn("Bedrock relay upload (mappings) failed: " + e.getMessage());
            }
        }
        if (uploaded > 0) {
            Logger.info("Pushed " + uploaded + " file(s) to Bedrock relay for proxy fallback.");
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Notify a player + the operator when {@link #sendResourcePack} fired before
     * the pack is ready (mix still running, upload not yet succeeded). The player
     * will get the pack automatically once {@link #sendToOnlinePlayersIfFirstUpload}
     * fires — they don't need to rejoin — but they need to KNOW the pack is
     * still being built rather than silently see no prompt and assume the server
     * is broken or the plugin is misconfigured.
     *
     * <p>This is the ROOT-CAUSE warning for "no resource pack visible on this
     * server." If the backend's pack isn't built yet, neither Java nor Bedrock
     * delivery can ever succeed — so the operator should see this BEFORE chasing
     * the Bedrock-side "pack not ready" modal Geyser fires on the proxy.</p>
     */
    private static void warnPackNotReady(Player player) {
        // Operator-facing: loud console banner so it's hard to miss.
        Logger.warn("=====================================================================");
        Logger.warn("⚠  RSPM: '" + player.getName() + "' joined before the resource pack is ready.");
        Logger.warn("⚠  Pack state: mixed=" + (Mix.getFinalResourcePack() != null)
                + ", uploadedOrSelfHosted=" + (done || selfHostedUrl != null));
        Logger.warn("⚠  Effect: they're connected with NO resource pack right now.");
        Logger.warn("⚠  Auto-recovery: they'll receive the pack automatically as soon as");
        Logger.warn("⚠                 mixing+upload finish (typically <30s after boot).");
        Logger.warn("⚠                 No need for them to rejoin.");
        Logger.warn("=====================================================================");

        // Player-facing: deferred 1 tick so the join greeting/welcome messages
        // don't shove our warning off-screen. Bedrock-via-Floodgate players
        // also see this (Floodgate routes them through the same PlayerJoinEvent),
        // which is fine — for those players this signal supersedes the proxy-
        // side Geyser modal anyway since the root cause is on the backend.
        Bukkit.getScheduler().runTaskLater(ResourcePackManager.plugin, () -> {
            if (!player.isOnline()) return;
            player.sendMessage("§c§l⚠ §e§l[RSPM] §r§eResource pack still building on this server.");
            player.sendMessage("§7You'll receive it automatically in a few seconds — no need to rejoin.");
        }, 20L);
    }

    /**
     * On first success after (re)initialize(), push the pack to any players
     * already online — covers the /reload scenario where players don't trigger
     * a fresh PlayerJoinEvent.
     */
    private static void sendToOnlinePlayersIfFirstUpload() {
        if (firstUpload) {
            broadcastResourcePackSync();
            firstUpload = false;
        }
    }

    /**
     * Broadcast the current pack to all online players on the main thread.
     * Player#addResourcePack / setResourcePack are sync-only per the Spigot API
     * contract; callers from async contexts (the keep-alive runnable, upload
     * error paths) must hop to the main thread before iterating online players.
     */
    private static void broadcastResourcePackSync() {
        Bukkit.getScheduler().runTask(ResourcePackManager.plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                sendResourcePack(p);
            }
        });
    }

    /**
     * Apply orchestration-level reactions to an upload error returned by the
     * client (e.g. resetting {@link #rspUUID} on {@code SESSION_NOT_FOUND} so
     * the next keep-alive tick reinitializes the session). The client itself
     * has already emitted detailed log lines for the operator.
     */
    private static void handleUploadError(RspError error) {
        if (error == null) return;
        String code = error.code();
        if (code != null && code.equals("SESSION_NOT_FOUND")) {
            rspUUID = null; // Trigger re-initialization on next keep-alive tick
        }
    }

    /**
     * Start a local HTTP server (or reuse an already-running one) that serves
     * the current {@link Mix#getFinalResourcePack()} zip. Called from any
     * upload-error path and from the {@code selfHostForce} short-circuit.
     *
     * <p>The server is started once and left running for the plugin's lifetime;
     * {@code PackHttpServer} reads the file per-request, so a re-mix that
     * rewrites the same zip path is picked up automatically without
     * restarting the server.</p>
     *
     * @return {@code true} if a server is now serving the pack (whether newly
     * started or already running), {@code false} if self-host is disabled,
     * the pack file is missing, or the port is unavailable.
     */
    private static boolean fallbackToSelfHost() {
        if (!DefaultConfig.isSelfHostEnabled() && !DefaultConfig.isSelfHostForce()) return false;
        File pack = Mix.getFinalResourcePack();
        if (pack == null) return false;
        // In network mode the server may already be running (started by
        // startBackendHttpServerIfNeeded for the Bedrock-output routes). Reuse it.
        if (selfHostServer != null) {
            // Already running — derive and cache the self-host URL, mark done,
            // and notify players. The URL is the pack path on the same server.
            if (selfHostedUrl == null) {
                selfHostedUrl = selfHostServer.urlOn(resolveExternalHost());
                Logger.info("Self-hosting pack at " + selfHostedUrl);
            }
            done = true;
            broadcastResourcePackSync();
            return true;
        }
        int port = resolveHttpPort();
        try {
            PackHttpServer server = startPackHttpServer(pack, port);
            String url = server.urlOn(resolveExternalHost());
            selfHostServer = server;
            selfHostedUrl = url;
            Logger.info("Self-hosting pack at " + url);
            done = true;
            broadcastResourcePackSync();
            return true;
        } catch (IOException e) {
            Logger.warn("Self-host fallback failed (port " + port
                    + " probably in use): " + e.getMessage());
            return false;
        }
    }

    /**
     * Network-mode only: start a {@link PackHttpServer} immediately so the
     * proxy plugin's NetworkSync can pull this backend's Bedrock-conversion
     * outputs from the very first poll, even before this backend has produced
     * any output. The pack-zip route 404s until {@link Mix#getFinalResourcePack()}
     * appears.
     *
     * <p>Also registers two Bedrock-conversion output routes
     * ({@link PackHttpServer#BEDROCK_PACK_PATH} and
     * {@link PackHttpServer#GEYSER_MAPPINGS_PATH}) so the proxy can pull the
     * latest converted pack + Geyser mappings on each poll. Both routes read
     * the file fresh per request and 404 cleanly until BedrockConversion has
     * produced output, so they're safe to wire up before the first conversion
     * has run.</p>
     */
    private static void startBackendHttpServerIfNeeded() {
        if (selfHostServer != null) return;
        int port = resolveHttpPort();
        try {
            File pack = Mix.getFinalResourcePack(); // may be null at this point
            PackHttpServer server = startPackHttpServer(pack, port);
            selfHostServer = server;
            registerBedrockOutputRoutes(server);
            Logger.info("Started backend HTTP server on port " + server.port()
                    + " (serving /rspm.zip, " + PackHttpServer.BEDROCK_PACK_PATH
                    + ", " + PackHttpServer.GEYSER_MAPPINGS_PATH + ")");
        } catch (IOException e) {
            // Loud multi-line ERROR: this backend is now invisible to the proxy.
            // Bedrock players will not get its content in the merged pack.
            Logger.warn("[ERROR] Backend HTTP server failed to bind on port " + port
                    + ": " + e.getMessage());
            Logger.warn("[ERROR] This backend's Bedrock resource pack will NOT reach the proxy directly.");
            Logger.warn("[ERROR] If another process is using port " + port + ", set selfHostPort to a different value");
            Logger.warn("[ERROR] in plugins/ResourcePackManager/config.yml, OR change networkHttpOffset-v2 to push");
            Logger.warn("[ERROR] the auto-derived port away from the collision.");
            // The relay-upload path (when implemented) will let this backend deliver its");
            // Bedrock pack via the magmaguy.com hoster — see project plan for the bridge endpoint.
        }
    }

    /**
     * Resolve the HTTP port for the backend's {@link PackHttpServer}.
     * <ul>
     *   <li>{@code selfHostPort >= 0}: explicit admin-configured port (back-compat).</li>
     *   <li>{@code selfHostPort == -1} (default sentinel): auto-derive as
     *       {@code Bukkit.getServer().getPort() + networkHttpOffset}. This guarantees
     *       a unique HTTP port per backend on a single-host deployment without any
     *       admin configuration, because each backend already has a unique MC port.</li>
     * </ul>
     */
    private static int resolveHttpPort() {
        int explicit = DefaultConfig.getSelfHostPort();
        if (explicit >= 0) return explicit;
        int mcPort = Bukkit.getServer().getPort();
        return mcPort + DefaultConfig.getNetworkHttpOffset();
    }

    /**
     * Register the two Bedrock-conversion output routes on the running server.
     * Files are resolved to absolute paths under the plugin data folder's
     * {@code output/} subdirectory — the same paths {@code BedrockConversion}
     * writes to. {@link PackHttpServer#registerFileRoute(File, String) reads
     * per-request} so a fresh BedrockConversion run is visible immediately
     * to the next proxy poll without restarting anything.
     */
    private static void registerBedrockOutputRoutes(PackHttpServer server) {
        File outputDir = new File(ResourcePackManager.plugin.getDataFolder(), "output");
        File bedrockZip = new File(outputDir, "ResourcePackManager_Bedrock.zip");
        File geyserMappings = new File(outputDir, "rspm_geyser_mappings.json");
        server.registerFileRoute(PackHttpServer.BEDROCK_PACK_PATH, bedrockZip, "application/zip");
        server.registerFileRoute(PackHttpServer.GEYSER_MAPPINGS_PATH, geyserMappings, "application/json");
    }

    /**
     * Construct + start the {@link PackHttpServer}. Centralises so the
     * network-mode startup path and the fallback-self-host path can't drift apart.
     */
    private static PackHttpServer startPackHttpServer(File pack, int port) throws IOException {
        return PackHttpServer.start(pack, port, "/rspm.zip");
    }

    /**
     * Cached public-IP result from {@link MagmaguyRspClient#detectPublicIp()},
     * computed once per {@link #initialize()} and reused thereafter so the
     * outbound call to api.ipify.org isn't repeated on every keep-alive tick.
     * {@code null} = not yet attempted, {@code Optional.empty()} = attempted
     * and failed (don't retry until next initialize).
     */
    private static volatile Optional<String> cachedPublicIp = null;

    /**
     * Exposes the cached public-IP detection result for diagnostic commands
     * (e.g. {@code /rspm status}). Returns {@code null} if detection hasn't
     * been attempted yet for this session, {@code Optional.empty()} if it
     * was attempted but failed, or {@code Optional.of(ip)} on success.
     */
    public static Optional<String> getCachedPublicIp() {
        return cachedPublicIp;
    }

    /**
     * Returns the host string the plugin would publish to clients right now.
     * Mainly for diagnostic display — does NOT run the public-IP probe if it
     * hasn't already, so calling this from a command thread is safe and cheap.
     */
    public static String currentResolvedHost() {
        return resolveExternalHost();
    }

    /**
     * Resolve the public host for the self-host URL. Order of preference:
     * <ol>
     *   <li>{@code selfHostExternalHost} config (admin-set; required for any
     *       non-LAN scenario where auto-detection can't reach the IP services).</li>
     *   <li>Cached result of {@link MagmaguyRspClient#detectPublicIp()} — the
     *       public IPv4 reported by api.ipify.org / checkip.amazonaws.com.
     *       Computed once per initialize() to avoid hammering the IP services.</li>
     *   <li>{@link Bukkit#getIp()} when non-empty and not {@code 0.0.0.0}
     *       (usually a LAN address, but better than nothing for self-host
     *       on a LAN-only deployment).</li>
     *   <li>{@link java.net.InetAddress#getLocalHost()} as a best-effort.</li>
     *   <li>{@code localhost} as a last resort.</li>
     * </ol>
     * When the new {@code preferSelfHost} flow is active, a non-routable result
     * (LAN/loopback) causes the reachability probe to be skipped and the
     * plugin falls through to remote hosting. See {@link #isNonRoutableHost(String)}.
     */
    private static String resolveExternalHost() {
        String configured = DefaultConfig.getSelfHostExternalHost();
        if (configured != null && !configured.isBlank()) return configured;

        // Auto-detect via ipify / AWS check-ip. Cached for the session.
        Optional<String> publicIp = cachedPublicIp;
        if (publicIp == null) {
            MagmaguyRspClient c = client;
            publicIp = (c != null) ? c.detectPublicIp() : Optional.empty();
            cachedPublicIp = publicIp;
            publicIp.ifPresent(ip -> Logger.info("Auto-detected public IPv4 for self-host URL: " + ip));
        }
        if (publicIp.isPresent()) return publicIp.get();

        String bukkitIp = Bukkit.getIp();
        if (bukkitIp != null && !bukkitIp.isBlank() && !bukkitIp.equals("0.0.0.0")) return bukkitIp;
        try {
            return java.net.InetAddress.getLocalHost().getHostAddress();
        } catch (java.net.UnknownHostException e) {
            return "localhost";
        }
    }
}
