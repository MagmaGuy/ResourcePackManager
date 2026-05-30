package com.magmaguy.resourcepackmanager.bedrock;

import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.config.DefaultConfig;
import com.magmaguy.resourcepackmanager.network.NetworkMode;
import org.bukkit.Bukkit;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.event.EventRegistrar;
import org.geysermc.geyser.api.event.bedrock.SessionLoadResourcePacksEvent;
import org.geysermc.geyser.api.pack.PackCodec;
import org.geysermc.geyser.api.pack.ResourcePack;

import java.io.File;

/**
 * Serves the latest mixed Bedrock resource pack per-session via Geyser's
 * {@link SessionLoadResourcePacksEvent}. This replaces the previous approach of
 * copying the pack zip into {@code <Geyser>/packs/} at startup — that copy was
 * always one server-boot behind because Geyser only scans {@code packs/} at its
 * own startup, so the pack served to Bedrock clients was the pack from the
 * PREVIOUS run, not the current mix. Worse, that stale pack stayed in Geyser's
 * memory for the rest of the session even after RSPM regenerated the pack on
 * disk, so the only way to recover was to delete the output folder and restart.
 * <p>
 * With this provider, every Bedrock joiner reads the pack live from
 * {@code <plugin>/output/ResourcePackManager_Bedrock.zip}. {@link PackCodec#path}
 * delegates to Geyser's {@code GeyserPathPackCodec} which re-opens the file per
 * {@code serialize()} call and invalidates its sha256/size cache when the file's
 * mtime changes. So if RSPM finishes mixing while the server is running, the
 * NEXT Bedrock player to join gets the fresh pack with no server restart.
 * <p>
 * Currently-connected Bedrock players keep the pack they received at their own
 * join time — that's a Bedrock protocol constraint (pack handshake happens at
 * connection time), not something this code controls.
 * <p>
 * Custom-item mappings ({@code rspm_geyser_mappings.json}) are NOT served by
 * this class — they're boot-frozen by Geyser, so {@link GeyserDeployer} still
 * copies them to disk for next boot.
 */
public final class GeyserPackProvider {

    private static boolean registered = false;

    private GeyserPackProvider() {
    }

    /**
     * Registers a {@link SessionLoadResourcePacksEvent} subscriber so every
     * Bedrock joiner picks up the latest mixed pack from disk. Safe to call
     * even when Geyser isn't installed — checks the plugin presence first.
     */
    public static void register() {
        if (registered) return;
        if (Bukkit.getPluginManager().getPlugin("Geyser-Spigot") != null) {
            // One-shot migration: prior RSPM versions copied our pack zip into Geyser's
            // packs/ folder. Geyser auto-loads everything in that folder at boot, so
            // leaving the legacy copy in place can keep stale Bedrock packs alive even
            // after Bedrock conversion is disabled.
            cleanupLegacyPackCopy();
        }
        if (!DefaultConfig.isBedrockConversionEnabled()) return;
        if (NetworkMode.isActive()) {
            // Backend is behind a proxy that owns Geyser. We don't register a
            // SessionLoadResourcePacks subscriber here because Geyser isn't on
            // this JVM. The proxy plugin handles Bedrock pack registration from
            // its side; backend RSPM only needs to ensure the pack zip is
            // produced so the proxy can fetch it. Silent on the routine
            // "we're a backend in network mode" case — /rspm status surfaces
            // the same info on demand and the network-mode boot banner only
            // added noise to every backend's startup.
            return;
        }
        if (Bukkit.getPluginManager().getPlugin("Geyser-Spigot") == null) {
            // Standalone server with no Geyser at all. Nothing to do.
            return;
        }
        try {
            GeyserApi api = GeyserApi.api();
            api.eventBus().subscribe(
                    EventRegistrar.of(ResourcePackManager.plugin),
                    SessionLoadResourcePacksEvent.class,
                    GeyserPackProvider::onSessionLoadResourcePacks);
            registered = true;
        } catch (Throwable t) {
            // Defensive: any incompatibility with the running Geyser version (the API
            // surface we compile against is 2.9.6, runtime may differ) shouldn't kill
            // the rest of RSPM's startup. Fall back silently — admins still get a
            // working Java pack, Bedrock clients just won't get RSPM's pack.
            Logger.warn("Failed to register Geyser pack provider; Bedrock clients will not receive the RSPM pack until you restart with a compatible Geyser. Reason: " + t.getMessage());
        }
    }

    private static void cleanupLegacyPackCopy() {
        try {
            java.nio.file.Path geyserPacksDir = GeyserApi.api().packDirectory();
            if (geyserPacksDir == null) return;
            java.nio.file.Path legacy = geyserPacksDir.resolve("ResourcePackManager_Bedrock.zip");
            // One-time silent migration: prior versions dropped the pack into
            // Geyser's packs/ folder. Just delete the leftover if found —
            // operator doesn't need to know.
            java.nio.file.Files.deleteIfExists(legacy);
        } catch (Throwable ignored) {
            // Best-effort cleanup; not fatal if it fails.
        }
    }

    /**
     * Unregisters all our Geyser event subscriptions. Called from plugin shutdown
     * so a {@code /reload} doesn't leak duplicate subscribers across reloads.
     */
    public static void unregister() {
        if (!registered) return;
        try {
            GeyserApi.api().eventBus().unregisterAll(EventRegistrar.of(ResourcePackManager.plugin));
        } catch (Throwable ignored) {
            // Best-effort; if Geyser is already torn down there's nothing to clean.
        }
        registered = false;
    }

    private static void onSessionLoadResourcePacks(SessionLoadResourcePacksEvent event) {
        File outputDir = new File(ResourcePackManager.plugin.getDataFolder(), "output");
        File packFile = new File(outputDir, "ResourcePackManager_Bedrock.zip");
        if (!packFile.isFile()) {
            // First boot before any mix has completed, or a failed mix wiped the
            // output. The user explicitly accepted this small window — the joiner
            // just doesn't get a pack this one time.
            return;
        }
        try {
            ResourcePack pack = ResourcePack.create(PackCodec.path(packFile.toPath()));
            event.register(pack);
        } catch (Throwable t) {
            Logger.warn("Failed to register Bedrock pack for session: " + t.getMessage());
        }
    }
}
