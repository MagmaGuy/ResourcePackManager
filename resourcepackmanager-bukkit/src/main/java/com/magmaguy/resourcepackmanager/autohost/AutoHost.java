package com.magmaguy.resourcepackmanager.autohost;

import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.config.DataConfig;
import com.magmaguy.resourcepackmanager.config.DefaultConfig;
import com.magmaguy.resourcepackmanager.mixer.Mix;
import com.magmaguy.resourcepackmanager.network.NetworkMode;
import com.magmaguy.resourcepackmanager.utils.ServerVersionHelper;
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
        if (!done && selfHostedUrl == null) return;
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
        // selfHostForce short-circuits the magmaguy.com flow entirely.
        if (DefaultConfig.isSelfHostForce()) {
            fallbackToSelfHost();
            return;
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

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

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
            Logger.warn("[ERROR] This backend's Bedrock resource pack will NOT reach the proxy until you fix this.");
            Logger.warn("[ERROR] If another process is using port " + port + ", set selfHostPort to a different value in");
            Logger.warn("[ERROR] plugins/ResourcePackManager/config.yml, OR change networkHttpOffset to push");
            Logger.warn("[ERROR] the auto-derived port away from the collision.");
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
     * Resolve the public host for the self-host URL. Order of preference:
     * <ol>
     *   <li>{@code selfHostExternalHost} config (admin-set; required for any
     *       non-LAN scenario).</li>
     *   <li>{@link Bukkit#getIp()} when non-empty and not {@code 0.0.0.0}.</li>
     *   <li>{@link java.net.InetAddress#getLocalHost()} as a best-effort.</li>
     *   <li>{@code localhost} as a last resort.</li>
     * </ol>
     * RPM does NOT probe reachability — if the resolved host isn't actually
     * reachable from clients, the pack download fails on the client side and
     * the admin must set {@code selfHostExternalHost}.
     */
    private static String resolveExternalHost() {
        String configured = DefaultConfig.getSelfHostExternalHost();
        if (configured != null && !configured.isBlank()) return configured;
        String bukkitIp = Bukkit.getIp();
        if (bukkitIp != null && !bukkitIp.isBlank() && !bukkitIp.equals("0.0.0.0")) return bukkitIp;
        try {
            return java.net.InetAddress.getLocalHost().getHostAddress();
        } catch (java.net.UnknownHostException e) {
            return "localhost";
        }
    }
}
