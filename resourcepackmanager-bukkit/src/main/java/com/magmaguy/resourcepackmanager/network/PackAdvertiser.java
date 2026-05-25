package com.magmaguy.resourcepackmanager.network;

import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.autohost.AutoHost;
import com.magmaguy.resourcepackmanager.config.DataConfig;
import com.magmaguy.resourcepackmanager.http.MagmaguyRspClient;
import com.magmaguy.resourcepackmanager.mixer.Mix;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Network-mode-only: advertises this backend's pack URL+SHA-1 to the proxy via
 * the {@code rspm:pack} plugin-messaging channel. Bukkit's
 * {@code Player.sendPluginMessage} requires a {@link Player} target — the proxy
 * intercepts the message server-bound and never delivers it to the client, but
 * we still need a player to "anchor" the message at, hence the
 * {@link PlayerJoinEvent} hook.
 *
 * <p>The advertised URL is the same one the backend pushes to Java clients in
 * the "no merged URL known" fallback path of
 * {@link AutoHost#sendResourcePack(Player)} — i.e. either the local self-host
 * URL (when self-hosting) or the magmaguy.com upload URL keyed by this
 * backend's {@code rspUUID}. This gives the proxy something to serve to
 * Bedrock clients via {@code GeyserBinder} until the server-side
 * {@code /rsp/network/<key>/manifest} endpoint ships and {@code NetworkSync}
 * starts producing a proper network-merged pack.</p>
 *
 * <p>Payload format (length-prefixed UTF strings via
 * {@link DataOutputStream#writeUTF}): backend UUID, pack URL, SHA-1 hex.</p>
 */
public final class PackAdvertiser implements Listener {

    public static final String CHANNEL = "rspm:pack";

    private static volatile PackAdvertiser instance;

    private final JavaPlugin plugin;

    private PackAdvertiser(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Wire the advertiser. No-op outside network mode (so standalone backends
     * don't register the channel or the listener at all). Idempotent — calling
     * twice is safe.
     */
    public static void initialize(JavaPlugin plugin) {
        if (!NetworkMode.isActive()) return;
        if (instance != null) return;
        PackAdvertiser advertiser = new PackAdvertiser(plugin);
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
        Bukkit.getPluginManager().registerEvents(advertiser, plugin);
        instance = advertiser;
        Logger.info("Backend pack advertiser registered on channel " + CHANNEL);
    }

    /**
     * Tear down the listener + channel registration. Called from
     * {@link ResourcePackManager#onDisable()} so a hot reload doesn't leak
     * duplicate listeners.
     */
    public static void shutdown() {
        PackAdvertiser current = instance;
        if (current == null) return;
        try {
            Bukkit.getMessenger().unregisterOutgoingPluginChannel(current.plugin, CHANNEL);
        } catch (Throwable ignored) {
            // best-effort
        }
        instance = null;
    }

    /**
     * Re-advertise to every online player. Called after a successful AutoHost
     * upload so existing players' proxy connections deliver the freshest URL
     * (e.g. after a re-mix that changed the SHA-1).
     */
    public static void advertiseToAll() {
        PackAdvertiser current = instance;
        if (current == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            current.sendAdvertTo(p);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        // Re-advertise on every join. The proxy uses the message source's server
        // connection to associate the advert with the right backend, and each
        // join gives us a fresh player channel to push through.
        sendAdvertTo(event.getPlayer());
    }

    private void sendAdvertTo(Player player) {
        if (!NetworkMode.isActive()) return;
        String url = pickAdvertUrl();
        if (url == null) return;
        String sha1Hex = Mix.getFinalSHA1();
        if (sha1Hex == null) sha1Hex = "";
        String backendUuid = DataConfig.getRspUUID();
        if (backendUuid == null || backendUuid.isBlank()) return;

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(baos)) {
            out.writeUTF(backendUuid);
            out.writeUTF(url);
            out.writeUTF(sha1Hex);
            player.sendPluginMessage(plugin, CHANNEL, baos.toByteArray());
        } catch (IOException e) {
            Logger.warn("Failed to send rspm:pack advert: " + e.getMessage());
        }
    }

    /**
     * Pick the URL to advertise. Mirrors the precedence used in
     * {@link AutoHost#sendResourcePack(Player)}'s fallback path: prefer the
     * self-hosted URL when self-hosting is active, otherwise the magmaguy.com
     * URL keyed by this backend's UUID once the upload completes.
     */
    private static String pickAdvertUrl() {
        String selfHosted = AutoHost.getSelfHostedUrl();
        if (selfHosted != null) return selfHosted;
        String uuid = AutoHost.getRspUUID();
        if (uuid == null || uuid.isBlank()) return null;
        if (!AutoHost.isDone()) return null;
        return MagmaguyRspClient.BASE_URL + uuid;
    }
}
