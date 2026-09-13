package com.magmaguy.resourcepackmanager.autohost;

import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.bedrock.BedrockConversion;
import com.magmaguy.resourcepackmanager.bedrock.BedrockOutputPublication;
import com.magmaguy.resourcepackmanager.config.DataConfig;
import com.magmaguy.resourcepackmanager.config.DefaultConfig;
import com.magmaguy.resourcepackmanager.mixer.Mix;
import com.magmaguy.resourcepackmanager.network.NetworkMode;
import com.magmaguy.resourcepackmanager.utils.RSPLogger;
import com.magmaguy.resourcepackmanager.utils.ServerVersionHelper;
import com.magmaguy.resourcepackmanager.http.BackendIdentity;
import com.magmaguy.resourcepackmanager.http.MagmaguyRspClient;
import com.magmaguy.resourcepackmanager.http.MagmaguyRspClient.UploadResult;
import com.magmaguy.resourcepackmanager.http.PackHttpServer;
import com.magmaguy.resourcepackmanager.http.RspError;
import com.magmaguy.resourcepackmanager.update.BackendPluginUpdateArtifactProvider;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

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
 * {@code NetworkSync} to pull. The pack-zip route (used by Java self-hosting)
 * 404s until {@link Mix#getFinalResourcePack()} appears; the Bedrock
 * routes 404 until BedrockConversion has produced output.</p>
 */
public class AutoHost {
    // Consistent UUID for identifying ResourcePackManager's pack when using multiple resource packs
    private static final UUID RESOURCE_PACK_UUID = UUID.fromString("a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d");

    /**
     * Delay before the join-time send. Pushing the pack on the exact tick a
     * player enters the PLAY phase races the client's join handshake — the
     * client silently drops the pack and the player sees missing / black-and-
     * purple textures until a manual {@code /rspm reload}. Deferring ~1s lands
     * the send on a settled client, the same footing as
     * {@link #broadcastResourcePackSync()} (used by reload and the first-upload
     * broadcast), which has always worked.
     */
    private static final long JOIN_SEND_DELAY_TICKS = 20L;
    /** Delay between self-heal resends triggered by a failed status report. */
    private static final long RESEND_DELAY_TICKS = 40L;
    /** Cap on automatic resends per player session before we stop and tell the operator. */
    private static final int MAX_RESEND_ATTEMPTS = 3;
    /**
     * Per-player resend counter, keyed by player UUID. Reset on (re)join and
     * cleared on success, decline, quit, or give-up. Concurrent map purely for
     * safety; all access is on the main thread.
     */
    private static final Map<UUID, Integer> resendAttempts = new ConcurrentHashMap<>();
    /** Prevent duplicate status events from queueing overlapping retries. */
    private static final Set<UUID> resendPending = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, BukkitTask> joinSendTasks = new ConcurrentHashMap<>();
    private static final Map<UUID, BukkitTask> resendTasks = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> playerSessionGenerations = new ConcurrentHashMap<>();
    private static final AtomicLong playerSessionSequence = new AtomicLong();

    // Timeout settings for HTTP requests (in seconds)
    private static final int DEFAULT_SOCKET_TIMEOUT = 60;
    private static final int UPLOAD_SOCKET_TIMEOUT = 1020; // 17 minutes; exceeds edge and hoster deadlines

    @Getter
    private static boolean done = false;

    private static BukkitTask keepAlive = null;
    @Getter
    private static String rspUUID = null;
    private static boolean firstUpload = true;

    /** Shared HTTP client. Reconstructed on each {@link #initialize()} call. */
    private static volatile MagmaguyRspClient client = null;

    /** Local self-host server when the magmaguy.com upload fails or is force-skipped. */
    private static volatile PackHttpServer selfHostServer = null;
    /** Atomically published file/digest pair consumed by the HTTP worker threads. */
    private static volatile PackHttpServer.FileRouteDescriptor javaPackRouteDescriptor = null;
    private static volatile PackHttpServer.FileRouteDescriptor bedrockPackRouteDescriptor = null;
    private static volatile PackHttpServer.FileRouteDescriptor bedrockMappingsRouteDescriptor = null;
    @Getter
    private static volatile String selfHostedUrl = null;
    /** Ensures the delivery outcome is announced once, not on every keep-alive tick. */
    private static boolean announcedDelivery = false;

    /**
     * Bedrock relay upload task. In network mode, this backend pushes its
     * {@code output/ResourcePackManager_Bedrock.zip} and
     * {@code output/rspm_geyser_mappings.json} to the magmaguy.com relay so
     * that a proxy which can't directly reach this backend's HTTP port still
     * has a path to fetch the files. Runs every {@link #RELAY_UPLOAD_PERIOD_TICKS}
     * to refresh the relay entry's TTL (server-side TTL is 30 min — we upload
     * well before that to be safe). Skipped if the files don't yet exist.
     */
    private static BukkitTask relayUploadTask = null;
    private static BukkitTask relayRetryTask = null;
    private static final Object RELAY_IO_LOCK = new Object();
    private static final AtomicLong BEDROCK_PUBLICATION_GENERATION = new AtomicLong();
    private static volatile boolean bedrockPublicationAuthorized = false;
    private static final long RELAY_RETRY_DELAY_TICKS = 30L * 20L;

    private static final AtomicLong LIFECYCLE_GENERATION = new AtomicLong();
    private static volatile LifecycleRun lifecycleRun = null;
    private static final long HOST_RETRY_PERIOD_TICKS = 30L * 20L;
    private static final long STILL_ALIVE_PERIOD_NANOS = 6L * 60L * 60L * 1_000_000_000L;

    private record LifecycleRun(long generation, BooleanSupplier cancellationRequested) {
        private boolean active() {
            return lifecycleRun == this
                    && generation == LIFECYCLE_GENERATION.get()
                    && (cancellationRequested == null || !cancellationRequested.getAsBoolean())
                    && !com.magmaguy.magmacore.MagmaCore.isShutdownRequested(ResourcePackManager.plugin);
        }
    }
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

    private static String ensureBackendId() {
        if (backendId == null) {
            backendId = BackendIdentity.loadOrCreate(
                    ResourcePackManager.plugin.getDataFolder().toPath().resolve("backend-id.txt"));
            RSPLogger.detail("RSPM backend-id for Bedrock relay: " + backendId);
        }
        return backendId;
    }

    private AutoHost() {
    }

    /**
     * Announces, once per delivery, how players are actually getting the pack.
     * <p>
     * This is the line an admin is looking for, and for most servers it is the only one they should
     * need. The pack can arrive by two very different routes — uploaded to magmaguy.com, or served
     * straight off this machine — and which one won determines where to look when something is
     * wrong, so the route is named rather than left implicit.
     * <p>
     * Guarded so repeated keep-alive ticks and re-sends do not repeat it; {@link #initialize()}
     * clears the guard when a genuinely new pack starts being delivered.
     */
    private static void announceDelivery(LifecycleRun run, String method, String url) {
        if (!run.active() || announcedDelivery) return;
        announcedDelivery = true;
        RSPLogger.outcome("Resource pack is live via " + method
                + (url == null || url.isBlank() ? "." : " — " + url));
    }

    public static void sendResourcePack(Player player) {
        if (isFloodgatePlayer(player)) {
            RSPLogger.detail("Skipping Java resource pack send for Bedrock/Floodgate player " + player.getName()
                    + "; proxy Geyser handles Bedrock pack delivery.");
            return;
        }

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

        RSPLogger.detail("Sending resource pack to " + player.getName());

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

    /**
     * Send the pack to a joining player, deferred off the join tick. Called from
     * {@code PlayerManager#onPlayerJoin} instead of {@link #sendResourcePack}
     * directly. See {@link #JOIN_SEND_DELAY_TICKS} for why the delay exists.
     *
     * <p>Cross-platform: uses only the core Bukkit scheduler, no Paper API.</p>
     */
    public static void scheduleJoinSend(Player player) {
        UUID id = player.getUniqueId();
        cancelPlayerTasks(id);
        resendAttempts.remove(id); // fresh session
        resendPending.remove(id);
        long session = playerSessionSequence.incrementAndGet();
        playerSessionGenerations.put(id, session);
        AtomicReference<BukkitTask> ownTask = new AtomicReference<>();
        BukkitTask task = Bukkit.getScheduler().runTaskLater(ResourcePackManager.plugin, () -> {
            try {
                if (player.isOnline() && playerSessionGenerations.getOrDefault(id, -1L) == session) {
                    sendResourcePack(player);
                }
            } finally {
                joinSendTasks.remove(id, ownTask.get());
            }
        }, JOIN_SEND_DELAY_TICKS);
        ownTask.set(task);
        joinSendTasks.put(id, task);
    }

    /** Drop a player's resend bookkeeping when they leave. */
    public static void forgetPlayer(UUID playerId) {
        playerSessionGenerations.put(playerId, playerSessionSequence.incrementAndGet());
        cancelPlayerTasks(playerId);
        resendAttempts.remove(playerId);
        resendPending.remove(playerId);
    }

    private static void cancelPlayerTasks(UUID playerId) {
        BukkitTask joinTask = joinSendTasks.remove(playerId);
        if (joinTask != null) joinTask.cancel();
        BukkitTask retryTask = resendTasks.remove(playerId);
        if (retryTask != null) retryTask.cancel();
    }

    private static void settlePlayer(UUID playerId) {
        playerSessionGenerations.put(playerId, playerSessionSequence.incrementAndGet());
        cancelPlayerTasks(playerId);
        resendAttempts.remove(playerId);
        resendPending.remove(playerId);
    }

    /**
     * Self-heal a failed pack delivery by reacting to the client's own status
     * report. Some clients (notably heavily-modded ones) can still drop the pack
     * even after the deferred join send. RSPM kept no delivery state before, so
     * the only recovery was a manual {@code /rspm reload}; here we re-push our
     * pack a bounded number of times before giving up — no operator action, no
     * rejoin needed.
     *
     * <p>Cross-platform: driven by the stock Bukkit
     * {@link PlayerResourcePackStatusEvent}. The status is matched by enum
     * <em>name</em> rather than constant so referencing 1.20.3+-only states
     * ({@code DISCARDED}, {@code INVALID_URL}) can't throw {@code NoSuchFieldError}
     * on older servers, and {@code getID()} is only called when
     * {@link ServerVersionHelper#supportsMultipleResourcePacks()} (i.e. when the
     * method exists).</p>
     */
    public static void handleResourcePackStatus(PlayerResourcePackStatusEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (isFloodgatePlayer(player)) {
            settlePlayer(id);
            return;
        }

        // On stacked-pack servers, ignore reports for other plugins' packs (we
        // tag ours with RESOURCE_PACK_UUID). On single-pack servers there's only
        // our pack, so every report is ours.
        if (ServerVersionHelper.supportsMultipleResourcePacks()) {
            UUID packId = event.getID();
            if (packId != null && !packId.equals(RESOURCE_PACK_UUID)) return;
        }

        switch (event.getStatus().name()) {
            case "SUCCESSFULLY_LOADED":
            case "DECLINED":
                // Settled: applied, or a deliberate opt-out. Nothing more to do.
                settlePlayer(id);
                return;
            case "FAILED_DOWNLOAD":
            case "DISCARDED": {
                if (!resendPending.add(id)) return;
                int attempts = resendAttempts.getOrDefault(id, 0);
                if (attempts >= MAX_RESEND_ATTEMPTS) {
                    Logger.warn("Resource pack delivery to " + player.getName() + " failed "
                            + attempts + "x (last status " + event.getStatus().name()
                            + "); giving up. They can retry with /rspm reload.");
                    settlePlayer(id);
                    return;
                }
                resendAttempts.put(id, attempts + 1);
                RSPLogger.detail("Resource pack " + event.getStatus().name() + " for " + player.getName()
                        + "; resending (attempt " + (attempts + 1) + "/" + MAX_RESEND_ATTEMPTS + ").");
                long session = playerSessionGenerations.computeIfAbsent(
                        id, ignored -> playerSessionSequence.incrementAndGet());
                AtomicReference<BukkitTask> ownTask = new AtomicReference<>();
                BukkitTask task = Bukkit.getScheduler().runTaskLater(ResourcePackManager.plugin, () -> {
                    try {
                        if (player.isOnline()
                                && playerSessionGenerations.getOrDefault(id, -1L) == session) {
                            sendResourcePack(player);
                        }
                    } finally {
                        if (playerSessionGenerations.getOrDefault(id, -1L) == session) {
                            resendPending.remove(id);
                        }
                        resendTasks.remove(id, ownTask.get());
                    }
                }, RESEND_DELAY_TICKS);
                ownTask.set(task);
                BukkitTask previous = resendTasks.put(id, task);
                if (previous != null && previous != task) previous.cancel();
                return;
            }
            case "INVALID_URL":
                // A bad URL is a hosting/config problem, not the join-tick race —
                // resending won't help, so don't loop on it.
                Logger.warn("Client " + player.getName() + " reported INVALID_URL for the resource pack"
                        + " — hosting/URL problem, not a timing one; not retrying.");
                settlePlayer(id);
                return;
            default:
                // ACCEPTED / DOWNLOADED (in-progress), FAILED_RELOAD, etc.: no action.
        }
    }

    private static boolean isFloodgatePlayer(Player player) {
        if (Bukkit.getPluginManager().getPlugin("floodgate") == null
                && Bukkit.getPluginManager().getPlugin("Floodgate") == null) {
            return false;
        }
        try {
            Class<?> floodgateApiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object floodgateApi = floodgateApiClass.getMethod("getInstance").invoke(null);
            Object result = floodgateApiClass.getMethod("isFloodgatePlayer", UUID.class)
                    .invoke(floodgateApi, player.getUniqueId());
            return Boolean.TRUE.equals(result);
        } catch (ClassNotFoundException | NoSuchMethodException ignored) {
            return false;
        } catch (Throwable throwable) {
            Logger.warn("Failed to check Floodgate player status for " + player.getName()
                    + ": " + throwable.getMessage());
            return false;
        }
    }

    /**
     * SHA-1 of the pack this JVM last successfully published, used to recognise a reload that
     * produced a byte-identical pack.
     */
    private static String publishedSHA1 = null;

    public static void initialize() {
        initialize(() -> false);
    }

    public static synchronized void initialize(BooleanSupplier runCancellation) {
        BooleanSupplier cancellation = runCancellation == null ? () -> false : runCancellation;
        if (cancellation.getAsBoolean()) return;
        File currentPack = Mix.getFinalResourcePack();
        javaPackRouteDescriptor = currentPack == null
                ? null
                : new PackHttpServer.FileRouteDescriptor(currentPack, Mix.getFinalSHA1());
        refreshBedrockPublicationAuthority();
        boolean javaDeliveryEnabled =
                DefaultConfig.isAutoHost() || DefaultConfig.isSelfHostForce();
        boolean networkModeActive = NetworkMode.isActive();
        if (!javaDeliveryEnabled && !networkModeActive) return;

        // A reload re-runs this path even when the pack did not change, and re-registering costs
        // two sequential round trips to the host (initialize, then sha1) before the second one
        // answers "already have it". That was most of what an admin waited through on /em reload.
        // If this JVM already published exactly this pack and still holds a live registration,
        // there is nothing to renegotiate — just re-offer it to whoever is online.
        // Deliberately not applied to self-hosting or network mode, which own extra state
        // (local HTTP server, relay uploads) that initialize() is responsible for rebuilding.
        if (done
                && rspUUID != null
                && javaDeliveryEnabled
                && !networkModeActive
                && !DefaultConfig.isSelfHostForce()
                && publishedSHA1 != null
                && publishedSHA1.equals(Mix.getFinalSHA1())) {
            RSPLogger.detail("Resource pack is unchanged and already hosted; skipping re-registration.");
            for (Player player : Bukkit.getOnlinePlayers()) sendResourcePack(player);
            return;
        }

        long generation = LIFECYCLE_GENERATION.incrementAndGet();
        LifecycleRun run = new LifecycleRun(generation, cancellation);
        lifecycleRun = run;
        if (!run.active()) {
            LIFECYCLE_GENERATION.incrementAndGet();
            lifecycleRun = null;
            return;
        }

        firstUpload = true;
        announcedDelivery = false;
        done = false;
        rspUUID = null;
        // Reset the public-IP cache so /rspm reload re-detects (admin may have
        // moved the server to a new network between boots / reloads).
        cachedPublicIp = null;
        if (keepAlive != null) {
            keepAlive.cancel();
            keepAlive = null;
        }
        if (relayUploadTask != null) {
            relayUploadTask.cancel();
            relayUploadTask = null;
        }
        if (relayRetryTask != null) {
            relayRetryTask.cancel();
            relayRetryTask = null;
        }

        // Close any client lingering from a previous initialize() (e.g. /rspm reload).
        if (client != null) {
            try {
                client.abortInFlight();
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
        if (networkModeActive) {
            startBackendHttpServerIfNeeded(run);
            startBedrockRelayUploadTask(run);
        }

        // Network mode still needs the backend HTTP/relay path when remote Java
        // pack hosting is disabled. Do not accidentally upload or push the Java
        // pack in that configuration.
        if (!javaDeliveryEnabled || Mix.getFinalResourcePack() == null) return;
        RSPLogger.detail("Starting autohost!");

        keepAlive = new BukkitRunnable() {
            int counter = 0;
            long nextStillAliveNanos = System.nanoTime() + STILL_ALIVE_PERIOD_NANOS;

            @Override
            public void run() {
                // Bail before doing any blocking HTTP work if the plugin is
                // disabling. cancel() alone doesn't interrupt a task that's
                // already mid-execution; without this check, an in-flight
                // upload (10s+ blocking) keeps the task alive past onDisable
                // and Bukkit nags about un-shutdown async tasks.
                if (!run.active() || isCancelled()) {
                    cancel();
                    return;
                }

                if (done) {
                    counter = 0;
                    // Remote hosting needs a periodic keep-alive. Self-hosting does not: the
                    // PackHttpServer remains live for this lifecycle and serves the current file
                    // directly. Falling through here when rspUUID is null used to re-run
                    // commitSelfHost() every 30 seconds, which re-sent the pack to every online
                    // Java player and left clients stuck in an endless resource-pack reload loop.
                    if (rspUUID == null) return;
                    if (System.nanoTime() < nextStillAliveNanos) return;
                    try {
                        sendStillAlive(run);
                        nextStillAliveNanos = System.nanoTime() + STILL_ALIVE_PERIOD_NANOS;
                    } catch (Exception e) {
                        if (run.active()) {
                            rspUUID = null;
                            done = false;
                            Logger.warn("Failed to autohost resource pack!");
                            e.printStackTrace();
                        }
                    }
                } else {
                    checkFileExistence(run);
                    if (!done && rspUUID == null && counter % 10 == 0) {
                        Logger.warn("Failed to connect to remote server to autohost the resource pack!");
                    }
                    counter++;
                }
            }
        }.runTaskTimerAsynchronously(ResourcePackManager.plugin, 0, HOST_RETRY_PERIOD_TICKS);
    }

    /** How the Java pack reaches players this lifecycle. */
    enum JavaHostingRoute {FORCED_SELF_HOST, SELF_HOST_FIRST, REMOTE}

    /** Selects Java delivery from hosting preferences; network topology owns HTTP lifetime separately. */
    static JavaHostingRoute resolveJavaHostingRoute(boolean selfHostForce,
                                                    boolean preferSelfHost,
                                                    boolean selfHostEnabled) {
        if (selfHostForce) return JavaHostingRoute.FORCED_SELF_HOST;
        if (preferSelfHost && selfHostEnabled) return JavaHostingRoute.SELF_HOST_FIRST;
        return JavaHostingRoute.REMOTE;
    }

    /**
     * Whether {@link #tearDownSelfHost()} may close the HTTP server. Standalone
     * it must (release the port); in network mode it must not — the same server
     * is the proxy-facing backend endpoint for {@code /bedrock.zip} and
     * {@code /mappings.json}, and a failed Java probe must not sever that.
     */
    static boolean shouldCloseServerOnTeardown(boolean networkModeActive) {
        return !networkModeActive;
    }

    private static void checkFileExistence(LifecycleRun run) {
        if (!run.active()) return;
        JavaHostingRoute route = resolveJavaHostingRoute(
                DefaultConfig.isSelfHostForce(),
                DefaultConfig.isPreferSelfHost(),
                DefaultConfig.isSelfHostEnabled());

        // selfHostForce short-circuits everything — straight to self-host, no probe, no remote.
        if (route == JavaHostingRoute.FORCED_SELF_HOST) {
            fallbackToSelfHost(run);
            return;
        }

        // Self-host-first (the preferSelfHost default): commit only after the
        // three-layer reachability check in trySelfHostFirst passes, falling back
        // to remote upload when any layer fails. See resolveJavaHostingRoute for
        // why proxy topology deliberately plays no part in this decision.
        if (route == JavaHostingRoute.SELF_HOST_FIRST) {
            if (trySelfHostFirst(run)) {
                return; // Self-host passed the reachability checks — we're done.
            }
            // Either the host looked non-routable, or the local HTTP server didn't
            // respond correctly to a localhost probe. Fall through to remote.
            RSPLogger.detail("Using magmaguy.com hosting for this resource pack.");
        }

        if (client == null || !run.active()) return;

        Optional<String> initResult;
        try {
            initResult = client.initialize(DataConfig.getRspUUID());
        } catch (IOException e) {
            if (!run.active()) return;
            Logger.warn("Failed to communicate with remote server!");
            e.printStackTrace();
            rspUUID = null;
            fallbackToSelfHost(run);
            return;
        }

        if (!run.active()) return;

        if (initResult.isEmpty()) {
            rspUUID = null;
            RSPLogger.detail("No resource pack found on the server! Uploading resource pack to the server...");
            fallbackToSelfHost(run);
            return;
        }
        rspUUID = initResult.get();
        DataConfig.setRspUUID(rspUUID);

        try {
            MagmaguyRspClient.Sha1Result sha1Result = client.sha1Check(rspUUID, Mix.getFinalSHA1());
            if (!run.active()) return;
            if (sha1Result.matched()) {
                // Remote server already has this resource pack
                RSPLogger.detail("Remote server already has this resource pack!");
                announceDelivery(run, "automatic hosting", MagmaguyRspClient.BASE_URL + rspUUID);
                done = true;
                publishedSHA1 = Mix.getFinalSHA1();
                sendToOnlinePlayersIfFirstUpload(run);
            } else if (sha1Result.errorOrNull() != null) {
                // Server returned a structured error during sha1 check — react
                // before wasting a multi-MB upload. SESSION_NOT_FOUND in
                // particular clears rspUUID so the next keep-alive tick
                // reinitializes; uploading against a dead session would just
                // burn bandwidth and fail.
                handleUploadError(run, sha1Result.errorOrNull());
                fallbackToSelfHost(run);
            } else {
                uploadFile(run);
            }
        } catch (IOException e) {
            if (!run.active()) return;
            Logger.warn("Failed to communicate with remote server during SHA1 check!");
            e.printStackTrace();
            fallbackToSelfHost(run);
        }
    }

    /**
     * Hybrid self-host-first sanity check:
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
     * fails. RSPM does not do per-player URL routing (LAN clients getting a
     * LAN URL, internet clients the public URL), so this limitation stands.
     * Workaround: testing from the host machine with a hairpin-broken
     * router requires {@code preferSelfHost: false} OR
     * {@code selfHostExternalHost: 127.0.0.1}.</p>
     *
     * @return {@code true} when all checks pass and self-host is now active.
     * {@code false} when the caller should fall through to remote hosting. On
     * {@code false}, the self-host server is torn down so the subsequent
     * remote-upload path doesn't announce a stale URL.
     */
    private static boolean trySelfHostFirst(LifecycleRun run) {
        if (!run.active() || Mix.getFinalResourcePack() == null) return false;

        // Layer 1: heuristic check on resolved external host.
        String host;
        try {
            java.net.URI externalUrl = SelfHostPublicUrl.parse(DefaultConfig.getSelfHostExternalUrl());
            host = externalUrl == null ? resolveExternalHost(run) : externalUrl.getHost();
        } catch (IllegalArgumentException exception) {
            Logger.warn(exception.getMessage());
            return false;
        }
        if (!run.active()) return false;
        if (host == null || isNonRoutableHost(host)) {
            RSPLogger.detail("Self-host check: local pack link is not public"
                    + formatOptionalDetail(host) + ". This is OK.");
            return false;
        }

        // Stand up the self-host server WITHOUT broadcasting yet — we only commit
        // (and push the URL to players) after the reachability probes below pass.
        if (!ensureSelfHostServer(run)) return false;
        if (selfHostedUrl == null) return false;

        // Layer 2: localhost self-probe — confirm the HTTP server is up and the
        // pack route serves a non-empty body. This catches everything between
        // "PackHttpServer.start() returned without throwing" and "an actual
        // client could download the pack" EXCEPT the external-firewall case.
        int port = (selfHostServer != null) ? selfHostServer.port() : -1;
        if (port <= 0 || !localhostSelfProbe(port)) {
            if (!run.active()) return false;
            RSPLogger.detail("Self-host check: local pack server did not answer correctly. This is OK.");
            tearDownSelfHost();
            return false;
        }

        // Layer 3: external reachability probe via magmaguy.com hoster.
        // The localhost probe just proved the server responds on 127.0.0.1; this
        // step proves the URL is reachable FROM THE PUBLIC INTERNET, which is
        // what actually matters for the clients we'll announce it to. Catches
        // the very common "public IP detected, port not forwarded at router"
        // failure mode that the previous two-layer check committed to silently.
        if (!externalReachabilityProbe(run, selfHostedUrl)) {
            if (!run.active()) return false;
            // externalReachabilityProbe logs the specific reason; teardown stops
            // the unreachable URL from being announced (see tearDownSelfHost for
            // the network-mode nuance) before falling through to remote upload.
            tearDownSelfHost();
            return false;
        }

        // All checks passed — NOW commit and push the verified URL to players.
        if (!run.active()) return false;
        commitSelfHost(run);
        RSPLogger.detail("Self-host sanity checks passed; using self-hosting at " + selfHostedUrl);
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
    private static boolean externalReachabilityProbe(LifecycleRun run, String url) {
        if (!run.active()) return false;
        MagmaguyRspClient c = client;
        if (c == null) {
            // No client (extremely unlikely — initialize() set it before
            // calling us). Don't block self-host on inability to probe.
            return true;
        }
        try {
            MagmaguyRspClient.ProbeResult result = c.probe(url);
            if (!run.active()) return false;
            if (result.reachable()) {
                RSPLogger.detail("External reachability probe via magmaguy.com: " + url
                        + " is reachable from the public internet (HTTP "
                        + result.status() + ", " + result.durationMs() + " ms). "
                        + "Committing to self-host.");
                return true;
            }
            RSPLogger.detail("Self-host check: local pack link is not public"
                    + formatOptionalDetail(result.reasonOrNull()) + ". This is OK.");
            return false;
        } catch (java.io.IOException e) {
            if (!run.active()) return false;
            // We couldn't talk to magmaguy.com to ask. Don't fail-closed —
            // see method javadoc decision policy.
            RSPLogger.detail("External reachability probe via magmaguy.com failed to "
                    + "communicate (" + e.getMessage() + "); keeping self-host. "
                    + "If clients can't reach the pack URL, set preferSelfHost: false.");
            return true;
        }
    }

    private static String formatOptionalDetail(String detail) {
        if (detail == null || detail.isBlank()) return "";
        return " (" + detail + ")";
    }

    /**
     * Open an HTTP connection to {@code 127.0.0.1:<port>/rspm.zip}, verify 200
     * status and non-empty body. Times out aggressively (3s) so a slow probe
     * can't drag out boot. Uses {@link java.net.HttpURLConnection} so we don't
     * pull in another Apache HC client dependency for one self-call.
     *
     * <p>Returns {@code false} on any anomaly (connection refused, non-200
     * status, empty body, timeout, IO error) — operators see the underlying
     * cause as part of the info log line.</p>
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
                RSPLogger.detail("Self-host check detail: local pack server returned HTTP " + code + ".");
                return false;
            }
            if (len == 0) {
                RSPLogger.detail("Self-host check detail: local pack file looked empty.");
                return false;
            }
            return true;
        } catch (java.io.IOException | java.net.URISyntaxException e) {
            RSPLogger.detail("Self-host check detail: local pack server did not answer"
                    + formatOptionalDetail(e.getMessage()) + ".");
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
     * remote-upload path can re-enter cleanly. Standalone, the server itself is
     * also closed (and its port released) — we'll re-create it on demand if a
     * later upload-failure path calls {@link #fallbackToSelfHost} again. In
     * network mode the server MUST survive: it doubles as the always-on backend
     * server the proxy pulls {@code /bedrock.zip} and {@code /mappings.json}
     * from, so a failed <em>Java</em> reachability probe only stops the Java
     * URL from being announced.</p>
     */
    private static void tearDownSelfHost() {
        if (shouldCloseServerOnTeardown(NetworkMode.isActive())) {
            PackHttpServer server = selfHostServer;
            if (server != null) {
                try {
                    server.close();
                } catch (Exception ignored) {
                    // expected during teardown
                }
            }
            selfHostServer = null;
        }
        selfHostedUrl = null;
        done = false;
    }

    /**
     * @return true if the host string is RFC1918, loopback, link-local, or
     * the unspecified address. Used to skip the reachability probe entirely
     * when the auto-detected host obviously isn't reachable from outside.
     */
    static boolean isNonRoutableHost(String host) {
        if (host == null || host.isBlank()) return true;
        String h = host.trim().toLowerCase(java.util.Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]") && h.length() > 2) {
            h = h.substring(1, h.length() - 1);
        }
        int zoneIndex = h.indexOf('%');
        if (zoneIndex >= 0) {
            h = h.substring(0, zoneIndex);
        }
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
        if (h.indexOf(':') >= 0) {
            try {
                java.net.InetAddress address = java.net.InetAddress.getByName(h);
                if (address.isAnyLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress()) {
                    return true;
                }
                byte[] bytes = address.getAddress();
                // fc00::/7 — IPv6 unique-local addresses. InetAddress does not
                // classify these as site-local even though they are never
                // globally routable.
                return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
            } catch (java.net.UnknownHostException ignored) {
                // A malformed configured hostname is handled by the later URL
                // and reachability checks. It is not evidence of a private IP.
            }
        }
        return false;
    }

    private static void uploadFile(LifecycleRun run) {
        if (!run.active() || client == null || rspUUID == null) return;
        RSPLogger.detail("Uploading resource!");

        UploadResult result;
        try {
            result = client.upload(rspUUID, Mix.getFinalResourcePack(), Mix.getFinalSHA1());
        } catch (IOException e) {
            if (!run.active()) return;
            Logger.warn("Failed to communicate with remote server during upload!");
            e.printStackTrace();
            fallbackToSelfHost(run);
            return;
        }

        if (!run.active()) return;

        if (result.success()) {
            RSPLogger.detail("Uploaded resource pack for automatic hosting! url: " + result.urlOrNull());
            announceDelivery(run, "automatic hosting", result.urlOrNull());
            done = true;
            publishedSHA1 = Mix.getFinalSHA1();
            sendToOnlinePlayersIfFirstUpload(run);
        } else {
            handleUploadError(run, result.errorOrNull());
            fallbackToSelfHost(run);
        }
    }

    private static void sendStillAlive(LifecycleRun run) throws IOException {
        if (!run.active() || client == null || rspUUID == null) return;
        try {
            if (!client.stillAlive(rspUUID)) {
                if (!run.active()) return;
                // Non-2xx — session may have expired. Reset UUID to trigger re-initialization.
                rspUUID = null;
                done = false;
            }
        } catch (IOException e) {
            if (!run.active()) return;
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

            activeClient.downloadDataCompliance(rspUUID, zipFile);

            File readMe = new File(ResourcePackManager.plugin.getDataFolder().getAbsolutePath()
                    + File.separatorChar + "data_compliance" + File.separatorChar + "ReadMe.md");
            try (InputStream inputStream = ResourcePackManager.plugin.getResource("ReadMe.txt")) {
                if (inputStream != null) {
                    Files.copy(inputStream, readMe.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } finally {
            if (ownsClient) {
                try {
                    activeClient.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    public static synchronized void shutdown() {
        // Invalidate every task before cancelling/aborting it. Any runnable that
        // was already inside blocking I/O will fail its post-I/O generation check
        // and cannot resurrect hosting state in the next enable cycle.
        LIFECYCLE_GENERATION.incrementAndGet();
        lifecycleRun = null;
        BEDROCK_PUBLICATION_GENERATION.incrementAndGet();
        bedrockPublicationAuthorized = false;
        for (BukkitTask task : joinSendTasks.values()) task.cancel();
        for (BukkitTask task : resendTasks.values()) task.cancel();
        joinSendTasks.clear();
        resendTasks.clear();
        playerSessionGenerations.clear();
        resendAttempts.clear();
        resendPending.clear();
        // Cleared so that a re-enable does a full registration again. The skip in initialize()
        // assumes the keepAlive task below is still running; leaving this set across a shutdown
        // would skip re-registering and leave the host entry with nothing keeping it alive.
        publishedSHA1 = null;
        announcedDelivery = false;
        if (keepAlive != null) keepAlive.cancel();
        if (relayUploadTask != null) {
            relayUploadTask.cancel();
            relayUploadTask = null;
        }
        if (relayRetryTask != null) {
            relayRetryTask.cancel();
            relayRetryTask = null;
        }
        MagmaguyRspClient c = client;
        if (c != null) c.abortInFlight();
        // Best-effort: drop this backend's relay entry on clean shutdown so
        // the proxy stops seeing it immediately rather than waiting up to
        // 30 min for TTL. The client uses a dedicated two-second request here;
        // if it fails, the hoster's TTL sweep will catch up.
        if (NetworkMode.isActive() && backendId != null
                && !MagmaguyRspClient.isRemoteRelayDisabled()) {
            try {
                String networkKey = NetworkMode.getNetworkKey();
                if (networkKey != null && !networkKey.isBlank()) {
                    // This client is deliberately independent of the already
                    // aborted lifecycle client above. Keep the shutdown delete
                    // bounded and serialized with any upload that is finishing.
                    try (MagmaguyRspClient deleteClient = new MagmaguyRspClient(
                            ResourcePackManager.plugin.getLogger(), 2, 2)) {
                        synchronized (RELAY_IO_LOCK) {
                            deleteClient.deleteBedrockRelayOnShutdown(
                                    networkKey, backendId);
                        }
                    }
                }
            } catch (Exception ignored) {
                // One bounded attempt only on shutdown; TTL covers us.
            }
        }
        // Abort any in-flight HTTP request (initialize / sha1 / upload). Without
        // this, a multi-MB upload can keep the async task alive past onDisable
        // and Bukkit nags about un-shutdown async tasks.
        if (c != null) {
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
        javaPackRouteDescriptor = null;
        bedrockPackRouteDescriptor = null;
        bedrockMappingsRouteDescriptor = null;
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
    private static void startBedrockRelayUploadTask(LifecycleRun run) {
        if (MagmaguyRspClient.isRemoteRelayDisabled()) {
            RSPLogger.detail("Remote Bedrock relay disabled by JVM system-test property; direct backend HTTP remains active.");
            return;
        }
        if (relayUploadTask != null) return;
        ensureBackendId();
        relayUploadTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!run.active() || isCancelled()) {
                    cancel();
                    return;
                }
                requestRelayReconcile(run, BEDROCK_PUBLICATION_GENERATION.get());
            }
        }.runTaskTimerAsynchronously(ResourcePackManager.plugin, 1L, RELAY_UPLOAD_PERIOD_TICKS);
    }

    /**
     * Called by the mix pipeline immediately after {@code BedrockConversion.generate()}
     * produces fresh output. Because Java hosting now starts BEFORE Bedrock conversion
     * (so Java clients don't wait on it), {@link #initialize()} — and with it the
     * recurring relay task's first tick — runs while the Bedrock files don't exist yet.
     * Without this explicit push, the relay would skip and not retry for ~25 min,
     * delaying the proxy's view of this backend's Bedrock content. No-op in standalone
     * mode (no network key) and harmless if the relay task isn't running.
     */
    public static void publishBedrockOutputs() {
        refreshBedrockPublicationAuthority();
        long publicationGeneration = BEDROCK_PUBLICATION_GENERATION.incrementAndGet();
        bedrockPublicationAuthorized = bedrockPackRouteDescriptor != null
                && DefaultConfig.isBedrockConversionEnabled();
        LifecycleRun run = lifecycleRun;
        if (run != null && run.active() && NetworkMode.isActive()) {
            requestRelayReconcile(run, publicationGeneration);
        }
    }

    /**
     * Withdraws both relay artifacts after an ordinary conversion failure. The
     * conversion may have been unable to delete a locked local file, so this is
     * deliberately unconditional instead of deriving relay state from disk.
     * Cancellation does not call this path and therefore retains last-good data.
     */
    public static void withdrawBedrockOutputs() {
        bedrockPackRouteDescriptor = null;
        bedrockMappingsRouteDescriptor = null;
        bedrockPublicationAuthorized = false;
        long publicationGeneration = BEDROCK_PUBLICATION_GENERATION.incrementAndGet();
        LifecycleRun run = lifecycleRun;
        if (run != null && run.active() && NetworkMode.isActive()) {
            requestRelayReconcile(run, publicationGeneration);
        }
    }

    /**
     * One-shot push of bedrock.zip + mappings.json to the relay. Called by the
     * recurring relay task and on demand via {@link #publishBedrockOutputs()} after
     * a fresh BedrockConversion.
     */
    private static void requestRelayReconcile(LifecycleRun run, long publicationGeneration) {
        if (MagmaguyRspClient.isRemoteRelayDisabled() || !run.active()) return;
        Bukkit.getScheduler().runTaskAsynchronously(ResourcePackManager.plugin,
                () -> reconcileRelay(run, publicationGeneration));
    }

    private static void reconcileRelay(LifecycleRun run, long requestedGeneration) {
        boolean retry = false;
        synchronized (RELAY_IO_LOCK) {
            if (!run.active()
                    || requestedGeneration != BEDROCK_PUBLICATION_GENERATION.get()
                    || MagmaguyRspClient.isRemoteRelayDisabled()) return;
            MagmaguyRspClient relayClient = client;
            String id = backendId;
            String networkKey = NetworkMode.getNetworkKey();
            if (relayClient == null || id == null || networkKey == null || networkKey.isBlank()) return;

            if (!bedrockPublicationAuthorized) {
                MagmaguyRspClient.RelayDeleteResult deletion = relayClient.deleteBedrockRelay(
                        networkKey, id, null);
                retry = !deletion.confirmed() && deletion.retryable();
            } else {
                File outputDir = new File(ResourcePackManager.plugin.getDataFolder(), "output");
                BedrockOutputPublication.Snapshot publication = BedrockOutputPublication.current(outputDir);
                if (publication == null) {
                    if (requestedGeneration == BEDROCK_PUBLICATION_GENERATION.get()) {
                        bedrockPublicationAuthorized = false;
                        bedrockPackRouteDescriptor = null;
                        bedrockMappingsRouteDescriptor = null;
                        MagmaguyRspClient.RelayDeleteResult deletion = relayClient.deleteBedrockRelay(
                                networkKey, id, null);
                        retry = !deletion.confirmed() && deletion.retryable();
                    }
                } else {
                    PackHttpServer server = selfHostServer;
                    if (server != null) announceBackendEndpoint(relayClient, networkKey, server.port());
                    if (!run.active() || requestedGeneration != BEDROCK_PUBLICATION_GENERATION.get()) return;

                    try {
                        // The ZIP embeds the complete set manifest and is uploaded
                        // last as the commit artifact. Until it changes, a new
                        // mappings entry cannot match the old ZIP manifest.
                        boolean sidecarReady;
                        boolean sidecarRetryable = true;
                        if (publication.hasMappings()) {
                            sidecarReady = relayClient.uploadBedrockRelay(
                                    networkKey, id, "mappings", publication.mappings(),
                                    publication.mappingsSha1()).isPresent();
                        } else {
                            MagmaguyRspClient.RelayDeleteResult deletion =
                                    relayClient.deleteBedrockRelay(
                                            networkKey, id, "mappings");
                            sidecarReady = deletion.confirmed();
                            sidecarRetryable = deletion.retryable();
                        }
                        if (!sidecarReady) {
                            retry = sidecarRetryable;
                        } else if (!run.active()
                                || requestedGeneration != BEDROCK_PUBLICATION_GENERATION.get()) {
                            return;
                        } else {
                            boolean zipCommitted = relayClient.uploadBedrockRelay(
                                    networkKey, id, "zip", publication.pack(),
                                    publication.packSha1()).isPresent();
                            retry = !zipCommitted;
                            if (zipCommitted) RSPLogger.detail(
                                    "Pushed authoritative Bedrock artifact set to relay for proxy fallback.");
                        }
                    } catch (IOException e) {
                        if (run.active()) Logger.warn("Bedrock relay artifact-set upload failed: " + e.getMessage());
                        retry = true;
                    }
                }
            }
        }
        if (retry && run.active()
                && requestedGeneration == BEDROCK_PUBLICATION_GENERATION.get()) {
            scheduleRelayRetry(run, requestedGeneration);
        }
    }

    private static synchronized void scheduleRelayRetry(LifecycleRun run, long publicationGeneration) {
        if (!run.active()
                || publicationGeneration != BEDROCK_PUBLICATION_GENERATION.get()) return;
        if (relayRetryTask != null) relayRetryTask.cancel();
        AtomicReference<BukkitTask> ownTask = new AtomicReference<>();
        BukkitTask task = Bukkit.getScheduler().runTaskLaterAsynchronously(
                ResourcePackManager.plugin,
                () -> {
                    synchronized (AutoHost.class) {
                        if (relayRetryTask == ownTask.get()) relayRetryTask = null;
                    }
                    if (run.active()
                            && publicationGeneration == BEDROCK_PUBLICATION_GENERATION.get()) {
                        reconcileRelay(run, publicationGeneration);
                    }
                },
                RELAY_RETRY_DELAY_TICKS);
        ownTask.set(task);
        relayRetryTask = task;
    }

    private static void refreshBedrockPublicationAuthority() {
        File outputDir = new File(ResourcePackManager.plugin.getDataFolder(), "output");
        BedrockOutputPublication.Snapshot publication = DefaultConfig.isBedrockConversionEnabled()
                ? BedrockOutputPublication.current(outputDir)
                : null;
        if (publication != null
                && !BedrockConversion.hasArtifactSetManifest(publication.pack())) {
            publication = null;
        }
        if (publication == null) {
            bedrockPackRouteDescriptor = null;
            bedrockMappingsRouteDescriptor = null;
            bedrockPublicationAuthorized = false;
            return;
        }
        bedrockPackRouteDescriptor = new PackHttpServer.FileRouteDescriptor(
                publication.pack(), publication.packSha1());
        bedrockMappingsRouteDescriptor = publication.hasMappings()
                ? new PackHttpServer.FileRouteDescriptor(
                publication.mappings(), publication.mappingsSha1())
                : null;
        bedrockPublicationAuthorized = true;
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

        // Admin-facing: deferred 1 tick so the join greeting/welcome messages
        // don't shove our warning off-screen. Regular players never see this —
        // the pack arrives automatically either way, so the chat copy is a
        // staff diagnostic, not a player notice.
        Bukkit.getScheduler().runTaskLater(ResourcePackManager.plugin, () -> {
            if (!player.isOnline()) return;
            if (!player.isOp() && !player.hasPermission("resourcepackmanager.*")) return;
            player.sendMessage("§c§l⚠ §e§l[RSPM] §r§eResource pack still building on this server.");
            player.sendMessage("§7You'll receive it automatically in a few seconds — no need to rejoin.");
        }, 20L);
    }

    /**
     * On first success after (re)initialize(), push the pack to any players
     * already online — covers the /reload scenario where players don't trigger
     * a fresh PlayerJoinEvent.
     */
    private static void sendToOnlinePlayersIfFirstUpload(LifecycleRun run) {
        if (run.active() && firstUpload) {
            broadcastResourcePackSync(run);
            firstUpload = false;
        }
    }

    /**
     * Broadcast the current pack to all online players on the main thread.
     * Player#addResourcePack / setResourcePack are sync-only per the Spigot API
     * contract; callers from async contexts (the keep-alive runnable, upload
     * error paths) must hop to the main thread before iterating online players.
     */
    private static void broadcastResourcePackSync(LifecycleRun run) {
        if (!run.active()) return;
        Bukkit.getScheduler().runTask(ResourcePackManager.plugin, () -> {
            if (!run.active()) return;
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
    private static void handleUploadError(LifecycleRun run, RspError error) {
        if (!run.active() || error == null) return;
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
    private static boolean fallbackToSelfHost(LifecycleRun run) {
        if (!run.active() || !ensureSelfHostServer(run)) return false;
        if (!run.active()) return false;
        commitSelfHost(run);
        return true;
    }

    /**
     * Ensure the self-host {@link PackHttpServer} is running and {@link #selfHostedUrl}
     * is populated, WITHOUT marking {@link #done} or broadcasting to players.
     *
     * <p>Split out from {@link #fallbackToSelfHost()} so {@link #trySelfHostFirst()}
     * can stand the server up purely to <em>probe</em> it (localhost + external
     * reachability) before deciding whether to commit. The previous code broadcast
     * the unverified self-host URL to every online player the moment the server
     * bound, then — if the reachability probe failed — tore it down and re-announced
     * the remote URL seconds later. Players saw a broken pack send followed by the
     * real one. Broadcasting is now deferred to {@link #commitSelfHost()}, which the
     * caller only invokes once the URL is proven reachable.</p>
     *
     * @return {@code true} if a server is serving the pack (newly started or reused),
     * {@code false} if self-host is disabled, the pack is missing, or the port is in use.
     */
    private static boolean ensureSelfHostServer(LifecycleRun run) {
        if (!run.active()) return false;
        if (!DefaultConfig.isSelfHostEnabled() && !DefaultConfig.isSelfHostForce()) return false;
        File pack = Mix.getFinalResourcePack();
        if (pack == null) return false;
        java.net.URI externalUrl;
        try {
            externalUrl = SelfHostPublicUrl.parse(DefaultConfig.getSelfHostExternalUrl());
        } catch (IllegalArgumentException exception) {
            Logger.warn(exception.getMessage());
            return false;
        }
        // In network mode the server may already be running (started by
        // startBackendHttpServerIfNeeded for the Bedrock-output routes). Reuse it.
        if (selfHostServer != null) {
            if (!run.active()) return false;
            if (selfHostedUrl == null) {
                String url = selfHostUrl(run, selfHostServer, externalUrl);
                if (!run.active() || url == null) return false;
                selfHostedUrl = url;
            }
            return true;
        }
        try {
            int port = resolveHttpPort();
            PackHttpServer server = startPackHttpServer(port);
            if (!run.active()) {
                server.close();
                return false;
            }
            selfHostServer = server;
            String url = selfHostUrl(run, server, externalUrl);
            if (!run.active() || url == null) {
                server.close();
                return false;
            }
            selfHostedUrl = url;
            return true;
        } catch (IOException e) {
            Logger.warn("Self-host fallback failed: " + e.getMessage());
            return false;
        }
    }

    private static String selfHostUrl(LifecycleRun run, PackHttpServer server, java.net.URI externalUrl) {
        if (externalUrl != null) return externalUrl.toASCIIString();
        String host = resolveExternalHost(run);
        return host == null ? null : server.urlOn(host);
    }

    /**
     * Mark self-host as the active delivery path and push the (already-verified)
     * URL to every online player. Only call after {@link #ensureSelfHostServer()}
     * succeeded AND the URL passed its reachability checks.
     */
    private static void commitSelfHost(LifecycleRun run) {
        if (!run.active()) return;
        announceDelivery(run, "self-hosting", selfHostedUrl);
        done = true;
        broadcastResourcePackSync(run);
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
    private static void startBackendHttpServerIfNeeded(LifecycleRun run) {
        if (!run.active()) return;
        if (selfHostServer != null) {
            registerBedrockOutputRoutes(selfHostServer);
            return;
        }
        final int port;
        try {
            port = resolveHttpPort();
        } catch (IOException e) {
            Logger.warn("[ERROR] Backend HTTP server configuration is invalid: " + e.getMessage());
            Logger.warn("[ERROR] This backend's Bedrock resource pack will NOT reach the proxy directly.");
            return;
        }
        try {
            PackHttpServer server = startPackHttpServerWithFallback(
                    port,
                    DefaultConfig.getSelfHostPort() == -1,
                    message -> Logger.warn("[WARN] " + message));
            if (!run.active()) {
                server.close();
                return;
            }
            selfHostServer = server;
            registerBedrockOutputRoutes(server);
            RSPLogger.detail("Started backend HTTP server on port " + server.port()
                    + " (serving /rspm.zip, " + PackHttpServer.BEDROCK_PACK_PATH
                    + ", " + PackHttpServer.GEYSER_MAPPINGS_PATH + ", and protected "
                    + PackHttpServer.EXECUTABLE_UPDATE_PATH + ")");
            if (run.active()) {
                announceBackendEndpoint(client, NetworkMode.getNetworkKey(), server.port());
            }
        } catch (IOException e) {
            // Loud multi-line ERROR: this backend is now invisible to the proxy.
            // Bedrock players will not get its content in the merged pack.
            Logger.warn("[ERROR] Backend HTTP server failed to bind on port " + port
                    + ": " + e.getMessage());
            Logger.warn("[ERROR] This backend's Bedrock resource pack will NOT reach the proxy directly.");
            Logger.warn("[ERROR] If another process is using port " + port + ", set selfHostPort to a different value");
            Logger.warn("[ERROR] in plugins/ResourcePackManager/config.yml, OR change networkHttpOffset-v2 to push");
            Logger.warn("[ERROR] the auto-derived port away from the collision.");
            // The relay-upload path can still deliver Bedrock output through magmaguy.com
            // if direct backend HTTP is impossible, but the local server should bind cleanly
            // for the fastest same-host/same-network path.
        }
    }

    /**
     * Resolve the HTTP port for the backend's {@link PackHttpServer}.
     * <ul>
     *   <li>{@code selfHostPort != -1}: explicit admin-configured port (back-compat).</li>
     *   <li>{@code selfHostPort == -1} (default sentinel): auto-derive as
     *       {@code Bukkit.getServer().getPort() + networkHttpOffset}. This guarantees
     *       a unique HTTP port per backend on a single-host deployment without any
     *       admin configuration, because each backend already has a unique MC port.
     *       The actual bound port is announced to proxies after startup.</li>
     * </ul>
     */
    private static int resolveHttpPort() throws IOException {
        int explicit = DefaultConfig.getSelfHostPort();
        return resolveHttpPort(
                explicit,
                Bukkit.getServer().getPort(),
                DefaultConfig.getNetworkHttpOffset());
    }

    static int resolveHttpPort(int explicit, int minecraftPort, int networkHttpOffset)
            throws IOException {
        if (explicit != -1) {
            return requireValidHttpPort(explicit, "configured selfHostPort");
        }
        long derived = (long) minecraftPort + networkHttpOffset;
        return requireValidHttpPort(
                derived,
                "derived HTTP port (Minecraft port " + minecraftPort
                        + " + networkHttpOffset-v2 " + networkHttpOffset + ")");
    }

    private static int requireValidHttpPort(long port, String source) throws IOException {
        if (port < 1 || port > 65535) {
            throw new IOException(source + " resolved to " + port
                    + "; HTTP ports must be between 1 and 65535");
        }
        return (int) port;
    }

    /**
     * Register the two Bedrock-conversion output routes on the running server.
     * Files are resolved to absolute paths under the plugin data folder's
     * {@code output/} subdirectory — the same paths {@code BedrockConversion}
     * writes to. {@link PackHttpServer#registerFileRoute(String, File, String)}
     * reads per-request so a fresh BedrockConversion run is visible immediately
     * to the next proxy poll without restarting anything.
     */
    private static void registerBedrockOutputRoutes(PackHttpServer server) {
        server.registerFileRoute(
                PackHttpServer.BEDROCK_PACK_PATH,
                () -> currentBedrockRouteDescriptor(false),
                "application/zip");
        server.registerFileRoute(
                PackHttpServer.GEYSER_MAPPINGS_PATH,
                () -> currentBedrockRouteDescriptor(true),
                "application/json");
        server.registerProtectedExecutableRoute(
                PackHttpServer.EXECUTABLE_UPDATE_PATH,
                () -> BackendPluginUpdateArtifactProvider.current(ResourcePackManager.plugin),
                NetworkMode::getNetworkKey);
    }

    private static PackHttpServer.FileRouteDescriptor currentBedrockRouteDescriptor(
            boolean mappings) {
        if (!bedrockPublicationAuthorized || !DefaultConfig.isBedrockConversionEnabled()) return null;
        File outputDir = new File(ResourcePackManager.plugin.getDataFolder(), "output");
        BedrockOutputPublication.Snapshot publication = BedrockOutputPublication.current(outputDir);
        if (publication == null || (mappings && !publication.hasMappings())) return null;
        return mappings
                ? new PackHttpServer.FileRouteDescriptor(
                publication.mappings(), publication.mappingsSha1())
                : new PackHttpServer.FileRouteDescriptor(
                publication.pack(), publication.packSha1());
    }

    /**
     * Construct + start the {@link PackHttpServer}. Centralises so the
     * network-mode startup path and the fallback-self-host path can't drift apart.
     */
    private static PackHttpServer startPackHttpServer(int port) throws IOException {
        return PackHttpServer.startWithDescriptor(
                () -> javaPackRouteDescriptor,
                port,
                "/rspm.zip");
    }

    /**
     * Bind the backend listener, falling back to an OS-assigned free port only
     * when the requested port was auto-derived. An explicitly configured port
     * remains an administrator contract and must fail loudly when occupied.
     * The actual bound port is announced to the proxy by the caller.
     */
    static PackHttpServer startPackHttpServerWithFallback(
            int preferredPort, boolean allowEphemeralFallback) throws IOException {
        return startPackHttpServerWithFallback(preferredPort, allowEphemeralFallback, ignored -> { });
    }

    private static PackHttpServer startPackHttpServerWithFallback(
            int preferredPort,
            boolean allowEphemeralFallback,
            java.util.function.Consumer<String> warningSink) throws IOException {
        try {
            return startPackHttpServer(preferredPort);
        } catch (IOException bindFailure) {
            if (!allowEphemeralFallback) throw bindFailure;
            warningSink.accept("Auto-derived backend HTTP port " + preferredPort
                    + " is unavailable (" + bindFailure.getMessage()
                    + "); retrying with an OS-assigned free port.");
            return startPackHttpServer(0);
        }
    }

    private static void announceBackendEndpoint(MagmaguyRspClient relayClient, String networkKey, int httpPort) {
        if (MagmaguyRspClient.isRemoteRelayDisabled()) return;
        if (relayClient == null) return;
        if (networkKey == null || networkKey.isBlank()) return;
        if (httpPort <= 0) return;
        String id = ensureBackendId();
        if (id == null || id.isBlank()) return;
        String configuredHost = DefaultConfig.getSelfHostExternalHost();
        if (configuredHost != null && configuredHost.isBlank()) configuredHost = null;
        try {
            relayClient.announceBedrockEndpoint(
                    networkKey,
                    id,
                    configuredHost,
                    Bukkit.getServer().getPort(),
                    httpPort);
        } catch (IOException e) {
            Logger.warn("Bedrock endpoint announcement failed: " + e.getMessage());
        }
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
     * hasn't already (returns a {@code "(not yet resolved)"} placeholder in
     * that case), so calling this from a command thread is safe and cheap.
     */
    public static String currentResolvedHost() {
        try {
            java.net.URI externalUrl = SelfHostPublicUrl.parse(DefaultConfig.getSelfHostExternalUrl());
            if (externalUrl != null) return externalUrl.getHost();
        } catch (IllegalArgumentException exception) {
            return "(invalid selfHostExternalUrl)";
        }
        return resolveExternalHost(false);
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
        return resolveExternalHost(true, null);
    }

    private static String resolveExternalHost(LifecycleRun run) {
        return resolveExternalHost(true, run);
    }

    /**
     * @param probeIfUnresolved when {@code true}, run (and cache) the public-IP
     *                          probe if it hasn't been attempted yet this session.
     *                          When {@code false} (diagnostic display), never
     *                          probe — return a {@code "(not yet resolved)"}
     *                          placeholder instead.
     */
    private static String resolveExternalHost(boolean probeIfUnresolved) {
        return resolveExternalHost(probeIfUnresolved, null);
    }

    private static String resolveExternalHost(boolean probeIfUnresolved, LifecycleRun run) {
        String configured = DefaultConfig.getSelfHostExternalHost();
        if (configured != null && !configured.isBlank()) return configured;

        // Auto-detect via ipify / AWS check-ip. Cached for the session.
        Optional<String> publicIp = cachedPublicIp;
        if (publicIp == null) {
            if (!probeIfUnresolved) return "(not yet resolved)";
            MagmaguyRspClient c = client;
            publicIp = (c != null) ? c.detectPublicIp() : Optional.empty();
            if (run != null && !run.active()) return null;
            cachedPublicIp = publicIp;
            publicIp.ifPresent(ip -> RSPLogger.detail("Auto-detected public IPv4 for self-host URL: " + ip));
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
