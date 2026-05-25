package com.magmaguy.resourcepackmanager.proxy;

import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.event.EventRegistrar;
import org.geysermc.geyser.api.event.bedrock.SessionLoadResourcePacksEvent;
import org.geysermc.geyser.api.pack.PackCodec;
import org.geysermc.geyser.api.pack.ResourcePack;

import java.util.function.BiConsumer;

/**
 * Bedrock-side glue. Subscribes to Geyser's {@link SessionLoadResourcePacksEvent}
 * via the proxy's Geyser API and registers a {@code PackCodec.path(...)} pointing
 * at the on-disk merged Bedrock pack file. Geyser serves the bytes from disk to
 * Bedrock clients via the Bedrock protocol — no HTTP server is involved on the
 * proxy.
 *
 * <p>Updates the registered file when {@link #onMergedPackReady(MergedPack)} is
 * called by {@link NetworkSync}. Per-session registration uses the latest pack
 * file in {@link #current}. The path is stable across re-merges (the merger
 * overwrites the same file atomically), so Geyser picks up new bytes on the
 * next session load.
 *
 * <p>This class is platform-neutral within the Geyser ecosystem — the same
 * instance works on Geyser-Velocity, Geyser-BungeeCord, Geyser-Waterfall,
 * Geyser-Standalone, etc. The proxy plugin instantiates it once and gives it
 * the platform-specific {@link EventRegistrar}.
 */
public final class GeyserBinder {

    private final ProxyLogger logger;
    private final EventRegistrar registrar;
    /**
     * Platform-specific notifier the proxy entry plugin wires in for
     * Java-side operator-visible warnings. Called with (bedrockPlayerName,
     * humanReadableReason) when a Bedrock session loads without a usable
     * RSPM pack. The entry plugin implementation should broadcast a chat
     * message to all online Java players on the proxy so operators see
     * "this Bedrock player isn't seeing models, tell them to reconnect"
     * without having to scrape the proxy log.
     *
     * <p>Nullable. When null (e.g. the entry plugin chose to skip wiring
     * a Java-side broadcaster), the modal popup + console banner still
     * fire — this is purely additive.</p>
     */
    private final BiConsumer<String, String> onPackUnavailableJavaBroadcast;
    private volatile MergedPack current;
    private volatile boolean registered;

    public GeyserBinder(ProxyLogger logger, EventRegistrar registrar,
                        BiConsumer<String, String> onPackUnavailableJavaBroadcast) {
        this.logger = logger;
        this.registrar = registrar;
        this.onPackUnavailableJavaBroadcast = onPackUnavailableJavaBroadcast;
    }

    /**
     * Subscribe to {@link SessionLoadResourcePacksEvent}. Idempotent. Safe to call
     * even if Geyser is briefly unreachable — exceptions are caught and logged so
     * the proxy plugin's startup doesn't fail.
     */
    public void register() {
        if (registered) return;
        try {
            GeyserApi.api().eventBus().subscribe(
                    registrar,
                    SessionLoadResourcePacksEvent.class,
                    this::onSession);
            registered = true;
            logger.info("GeyserBinder registered — Bedrock clients will receive the network merged pack on session load.");
        } catch (Throwable t) {
            logger.warn("Failed to register GeyserBinder; Bedrock clients will not receive the network pack until restart with a compatible Geyser.", t);
        }
    }

    /**
     * Unsubscribe all our Geyser event subscriptions. Called from proxy-plugin shutdown
     * so a hot reload doesn't accumulate duplicate subscribers.
     */
    public void unregister() {
        if (!registered) return;
        try {
            GeyserApi.api().eventBus().unregisterAll(registrar);
        } catch (Throwable ignored) {
            // best-effort — Geyser may already be torn down
        }
        registered = false;
    }

    /**
     * Called by {@link NetworkSync} when a new merged pack is published. Subsequent
     * Bedrock sessions will see the new file.
     */
    public void onMergedPackReady(MergedPack pack) {
        this.current = pack;
    }

    private void onSession(SessionLoadResourcePacksEvent event) {
        MergedPack pack = current;
        if (pack == null) {
            // NetworkSync hasn't completed its first merge yet (no backend has
            // produced a /bedrock.zip we could pull). Loudly notify the operator
            // AND the player — without a pack the player will see no custom
            // models and the obvious next step is "reconnect once the merge
            // catches up." Silent failure here cost the user hours of debugging.
            notifyPackUnavailable(event, "the proxy hasn't completed its first merge cycle yet "
                    + "(backend(s) may still be starting up — typically resolves within 60s)");
            return;
        }
        java.io.File packFile = pack.packFile();
        if (packFile == null || !packFile.isFile()) {
            logger.warn("Merged Bedrock pack file is missing on disk: "
                    + (packFile == null ? "null" : packFile.getAbsolutePath()));
            notifyPackUnavailable(event, "the merged pack file is missing on the proxy "
                    + "(filesystem issue or wiped during boot — check proxy logs)");
            return;
        }
        try {
            event.register(ResourcePack.create(PackCodec.path(packFile.toPath())));
        } catch (Throwable t) {
            logger.warn("Failed to register pack for Bedrock session from " + packFile.getAbsolutePath(), t);
            notifyPackUnavailable(event, "Geyser refused the merged pack — "
                    + "see proxy log for the stack trace");
        }
    }

    /**
     * Surface a pack-not-available condition so the server operator and the
     * affected Bedrock player both KNOW something's wrong instead of guessing
     * why models are invisible. Three notification surfaces:
     * <ol>
     *   <li>A loud, multi-line console banner (operator-visible at proxy stdout
     *       and in any log file). Hard to miss while scanning logs.</li>
     *   <li>A Bedrock modal form ("popup") on the player's screen — most
     *       visible Bedrock UI element we can send via the documented Geyser API
     *       (the API has no direct sendTitle / sendMessage method on Connection;
     *       forms are the spec'd path for player-side notifications). The user
     *       sees the title + the explanation + a single "OK" button and can
     *       self-recover by disconnecting and reconnecting.</li>
     *   <li>(Fallback) if the form send fails for any reason — the console
     *       warning still went out and the operator can manually instruct the
     *       player to reconnect.</li>
     * </ol>
     *
     * @param event   the Geyser session event we're responding to (gives us the
     *                player's Bedrock connection)
     * @param reason  one-line explanation of WHY no pack — included in both the
     *                console banner and the form body so the operator and the
     *                player see the same root cause
     */
    private void notifyPackUnavailable(SessionLoadResourcePacksEvent event, String reason) {
        String playerName;
        GeyserConnection conn = null;
        try {
            conn = event.connection();
            playerName = conn.bedrockUsername();
        } catch (Throwable t) {
            playerName = "<unknown>";
        }

        // 1. Loud operator-facing console banner. Multi-line on purpose so it
        //    doesn't get lost in a busy proxy log.
        logger.warn("=====================================================================");
        logger.warn("⚠  RSPM: Bedrock player '" + playerName + "' connected but received NO pack.");
        logger.warn("⚠  Reason: " + reason);
        logger.warn("⚠  Effect: this player will see plain leather-horse-armor armor stands");
        logger.warn("⚠          instead of FMM custom models for the rest of this session.");
        logger.warn("⚠  Resolution: ask the player to disconnect and reconnect; the merged");
        logger.warn("⚠              pack will be served on their next session load.");
        logger.warn("=====================================================================");

        // 2. Java-side operator-visible broadcast — chat message to every
        //    Java player on the proxy (operators among them will see it).
        //    Without this, only the Bedrock player gets the modal and only the
        //    proxy console gets the banner; in-game Java admins are blind to
        //    the Bedrock player's bad state. The entry plugin provides the
        //    actual broadcaster (platform-specific Adventure / BaseComponent),
        //    we just hand off the (player, reason) pair.
        if (onPackUnavailableJavaBroadcast != null) {
            try {
                onPackUnavailableJavaBroadcast.accept(playerName, reason);
            } catch (Throwable t) {
                // Broadcaster failed — console banner above is the fallback.
            }
        }

        // 3. Modal popup on the Bedrock player's screen so they self-recover
        //    without operator intervention. Forms are the Geyser-documented
        //    cross-platform path for in-game Bedrock notifications.
        if (conn != null) {
            try {
                ModalForm form = ModalForm.builder()
                        .title("§c§l⚠ Resource Pack Not Ready")
                        .content(
                                "§eThe ResourcePackManager network pack isn't available yet.\n"
                                        + "\n"
                                        + "§7Why: §f" + reason + "\n"
                                        + "\n"
                                        + "§a✔  Disconnect and reconnect §7once the proxy reports the merged pack is published.\n"
                                        + "§7   You will then see all custom models correctly."
                        )
                        .button1("§a§lOK — I'll Reconnect")
                        .button2("§7Dismiss")
                        .build();
                conn.sendForm(form);
            } catch (Throwable t) {
                // Form send is best-effort — the console banner above is the
                // authoritative signal. Don't propagate; we don't want a form
                // failure to also block the session event.
            }
        }
    }
}
