package com.magmaguy.resourcepackmanager.autohost;

import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.config.DataConfig;
import com.magmaguy.resourcepackmanager.config.DefaultConfig;
import com.magmaguy.resourcepackmanager.mixer.Mix;
import com.magmaguy.resourcepackmanager.utils.ServerVersionHelper;
import com.magmaguy.rspm.http.MagmaguyRspClient;
import com.magmaguy.rspm.http.MagmaguyRspClient.UploadResult;
import com.magmaguy.rspm.http.RspError;
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
 * shared {@link MagmaguyRspClient} from {@code rpm-http-common}.</p>
 */
public class AutoHost {
    // Consistent UUID for identifying ResourcePackManager's pack when using multiple resource packs
    private static final UUID RESOURCE_PACK_UUID = UUID.fromString("a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d");

    // Timeout settings for HTTP requests (in seconds)
    private static final int DEFAULT_SOCKET_TIMEOUT = 60;
    private static final int UPLOAD_SOCKET_TIMEOUT = 300; // 5 minutes for file uploads

    @Setter
    private static boolean done = false;

    private static BukkitTask keepAlive = null;
    @Getter
    private static String rspUUID = null;
    @Setter
    private static boolean firstUpload = true;

    /** Shared HTTP client. Reconstructed on each {@link #initialize()} call. */
    private static volatile MagmaguyRspClient client = null;

    private AutoHost() {
    }

    public static void sendResourcePack(Player player) {
        if (rspUUID == null || !done) return;
        Logger.info("Sending resource pack to " + player.getName());

        String url = MagmaguyRspClient.BASE_URL + rspUUID;
        byte[] hash = Mix.getFinalSHA1Bytes();
        String prompt = DefaultConfig.getResourcePackPrompt();
        boolean force = DefaultConfig.isForceResourcePack();

        if (ServerVersionHelper.supportsMultipleResourcePacks()) {
            // 1.20.3+ supports multiple resource packs - use addResourcePack to coexist with other plugins
            player.addResourcePack(RESOURCE_PACK_UUID, url, hash, prompt, force);
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
        if (client == null) return;

        Optional<String> initResult;
        try {
            initResult = client.initialize(DataConfig.getRspUUID());
        } catch (IOException e) {
            Logger.warn("Failed to communicate with remote server!");
            e.printStackTrace();
            rspUUID = null;
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
            } else {
                uploadFile();
            }
        } catch (IOException e) {
            Logger.warn("Failed to communicate with remote server during SHA1 check!");
            e.printStackTrace();
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
            return;
        }

        if (result.success()) {
            Logger.info("Uploaded resource pack for automatic hosting! url: " + result.urlOrNull());
            done = true;
            sendToOnlinePlayersIfFirstUpload();
        } else {
            handleUploadError(result.errorOrNull());
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
            for (Player player : Bukkit.getOnlinePlayers()) {
                AutoHost.sendResourcePack(player);
            }
        }
        firstUpload = false;
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
}
