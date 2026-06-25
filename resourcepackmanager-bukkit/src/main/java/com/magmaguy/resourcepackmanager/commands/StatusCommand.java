package com.magmaguy.resourcepackmanager.commands;

import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.autohost.AutoHost;
import com.magmaguy.resourcepackmanager.config.DefaultConfig;
import com.magmaguy.resourcepackmanager.http.MagmaguyRspClient;
import com.magmaguy.resourcepackmanager.mixer.Mix;
import com.magmaguy.resourcepackmanager.network.NetworkMode;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.util.List;
import java.util.Optional;

/**
 * {@code /rspm status} — operator-facing diagnostic dump.
 *
 * <p>The intent is "show me everything an operator could plausibly need when
 * something's broken, in one shot." Specifically:</p>
 * <ul>
 *   <li>Plugin meta — version, deploy mode (standalone vs network backend), network key</li>
 *   <li>Java pack state — mixed/not, file size, sha1, output path</li>
 *   <li>Bedrock pack state — generated/not, file size, Geyser mappings present, Geyser folder</li>
 *   <li>Hosting state — which delivery path is active (self-host vs remote vs neither), the
 *       actual URL clients will see, the resolved external host (raw + auto-detected public IP)</li>
 *   <li>Config flags — all the knobs that control the above so the operator doesn't have to
 *       open config.yml in another window</li>
 *   <li>Integration sanity — Floodgate and Geyser plugin presence, autoHost flag</li>
 * </ul>
 *
 * <p>Output is rendered with MagmaCore's color-tag syntax (&amp;-prefixed); the
 * client renders this in the chat / console as legacy section-symbol formatting.
 * Sections are visually separated by blank lines for readability in the chat log.</p>
 *
 * <p><b>Threading:</b> all values read here are either {@code volatile} accessors
 * or simple {@code static} config fields populated at boot. No I/O — calling
 * this command must not slow down the main thread.</p>
 *
 * <p><b>Permission:</b> {@code resourcepackmanager.*} (same as reload, since the
 * output reveals internal-state details that aren't useful to non-admins).</p>
 */
public class StatusCommand extends AdvancedCommand {
    public StatusCommand() {
        super(List.of("status"));
        setDescription("Show RSPM's current status (pack state, hosting mode, config, integrations).");
        setPermission("resourcepackmanager.*");
        setUsage("/rspm status");
    }

    @Override
    public void execute(CommandData commandData) {
        CommandSender sender = commandData.getCommandSender();

        // ---------- Plugin meta ----------
        Logger.sendMessage(sender, "&8&m----- &6&lRSPM Status &8&m-----");
        Logger.sendMessage(sender, "&7Version: &f" + ResourcePackManager.plugin.getDescription().getVersion());
        Logger.sendMessage(sender, "&7Deploy mode: &f" + (NetworkMode.isActive() ? "network-backend" : "standalone"));
        if (NetworkMode.isActive()) {
            // Auto-derived from plugins/floodgate/key.pem; never a manual config field.
            // Showing the live resolved value (masked) so operators can confirm both
            // proxy and backend resolve to the same last-4 chars without exposing the
            // full secret to anyone who might be screen-sharing the command output.
            String key = NetworkMode.getNetworkKey();
            Logger.sendMessage(sender, "&7Network key: &f" + (key == null || key.isBlank()
                    ? "&c(not derived — install Floodgate on this backend)"
                    : "&a" + maskKey(key)));
        }
        Logger.sendMessage(sender, "");

        // ---------- Java pack state ----------
        File javaPack = Mix.getFinalResourcePack();
        boolean javaReady = javaPack != null && javaPack.isFile();
        Logger.sendMessage(sender, "&8&m----- &eJava Pack &8&m-----");
        Logger.sendMessage(sender, "&7Mixed: " + bool(javaReady));
        if (javaReady) {
            Logger.sendMessage(sender, "&7  Path: &f" + javaPack.getAbsolutePath());
            Logger.sendMessage(sender, "&7  Size: &f" + humanBytes(javaPack.length()));
            String sha1 = Mix.getFinalSHA1();
            if (sha1 != null && sha1.length() >= 8) {
                Logger.sendMessage(sender, "&7  SHA1 (first 8): &f" + sha1.substring(0, 8));
            }
        }
        Logger.sendMessage(sender, "");

        // ---------- Bedrock pack state ----------
        Logger.sendMessage(sender, "&8&m----- &bBedrock Pack &8&m-----");
        boolean bedrockEnabled = DefaultConfig.isBedrockConversionEnabled();
        Logger.sendMessage(sender, "&7Conversion enabled: " + bool(bedrockEnabled));
        File outputDir = new File(ResourcePackManager.plugin.getDataFolder(), "output");
        File bedrockZip = new File(outputDir, "ResourcePackManager_Bedrock.zip");
        File geyserMappings = new File(outputDir, "rspm_geyser_mappings.json");
        boolean bedrockReady = bedrockZip.isFile();
        boolean mappingsReady = geyserMappings.isFile();
        if (bedrockEnabled) {
            Logger.sendMessage(sender, "&7Bedrock pack: " + bool(bedrockReady)
                    + (bedrockReady ? " &8(" + humanBytes(bedrockZip.length()) + ")" : ""));
            Logger.sendMessage(sender, "&7Geyser mappings: " + bool(mappingsReady)
                    + (mappingsReady ? " &8(" + humanBytes(geyserMappings.length()) + ")" : ""));
            Logger.sendMessage(sender, "&7Auto-deploy to Geyser: " + bool(DefaultConfig.isBedrockAutoDeployToGeyser()));
            String geyserFolder = DefaultConfig.getBedrockGeyserFolder();
            if (geyserFolder != null && !geyserFolder.isBlank()) {
                Logger.sendMessage(sender, "&7Geyser folder override: &f" + geyserFolder);
            }
        }

        // Diagnostic: if conversion is enabled but the pack didn't get produced,
        // explain why in operator-readable terms. This is the most common
        // mis-diagnosed failure mode — operators see "Bedrock pack: ✗ no" and
        // assume the plugin is broken, when in reality the conversion was
        // skipped intentionally because no Bedrock target was detected. Calling
        // those reasons out here saves a Discord-support round trip.
        if (bedrockEnabled && !bedrockReady) {
            boolean networkActive = NetworkMode.isActive();
            boolean floodgate = Bukkit.getPluginManager().isPluginEnabled("floodgate");
            boolean geyserSpigot = Bukkit.getPluginManager().isPluginEnabled("Geyser-Spigot");
            // bedrockTargetPresent reflects the actual gate in
            // BukkitBedrockConverterContext#isBedrockTargetPresent — keep these
            // two boolean expressions in sync.
            boolean bedrockTargetPresent = networkActive || floodgate || geyserSpigot;
            Logger.sendMessage(sender, "");
            Logger.sendMessage(sender, "&c⚠ Bedrock pack is not on disk — diagnostic:");
            if (!bedrockTargetPresent) {
                Logger.sendMessage(sender, "&c  No Bedrock target detected (no local Floodgate/Geyser-Spigot,");
                Logger.sendMessage(sender, "&c  not in network mode). Conversion intentionally skipped.");
                Logger.sendMessage(sender, "&c  Fix: install Floodgate on this backend (proxy setup) OR");
                Logger.sendMessage(sender, "&c  Geyser-Spigot (standalone setup), then /rspm reload.");
            } else if (networkActive && !bedrockReady) {
                // We ARE supposed to convert (network mode active) but the file is missing.
                // Most likely: mixer hasn't run yet, OR the converted pack is being
                // regenerated this very moment. Tell the operator both possibilities so
                // they can wait OR hunt for a stack trace upthread.
                Logger.sendMessage(sender, "&c  Network mode is active so this backend SHOULD produce a");
                Logger.sendMessage(sender, "&c  Bedrock pack. The file is missing — likely causes:");
                Logger.sendMessage(sender, "&c    • First mix cycle hasn't completed yet (wait ~30s after boot).");
                Logger.sendMessage(sender, "&c    • Mix scanned 0 items (check console for");
                Logger.sendMessage(sender, "&c      'Generic scanner: discovered 0 items definition files').");
                Logger.sendMessage(sender, "&c    • Conversion threw — search console for");
                Logger.sendMessage(sender, "&c      'BedrockConverter' WARN/ERROR lines.");
            }
        }

        // Diagnostic: network-mode backend with no proxy-side handoff path. The
        // backend produced (or will produce) a Bedrock pack, but the proxy needs
        // to actually serve it. If the proxy plugin isn't installed there's
        // nothing on this backend to detect that directly — but we can at least
        // remind the operator to check.
        if (NetworkMode.isActive() && bedrockReady) {
            Logger.sendMessage(sender, "&7  &oNetwork mode: pack is fetched by the proxy. If Bedrock");
            Logger.sendMessage(sender, "&7  &oclients still see vanilla items, verify the proxy has");
            Logger.sendMessage(sender, "&7  &oResourcePackManager.jar loaded");
            Logger.sendMessage(sender, "&7  &oand check the proxy log for 'ResourcePackManager' lines.");
        }
        Logger.sendMessage(sender, "");

        // ---------- Hosting / delivery ----------
        Logger.sendMessage(sender, "&8&m----- &dHosting &8&m-----");
        Logger.sendMessage(sender, "&7autoHost: " + bool(DefaultConfig.isAutoHost()));
        Logger.sendMessage(sender, "&7preferSelfHost: " + bool(DefaultConfig.isPreferSelfHost())
                + " &7selfHostForce: " + bool(DefaultConfig.isSelfHostForce())
                + " &7selfHostEnabled: " + bool(DefaultConfig.isSelfHostEnabled()));

        // Active delivery path resolved by looking at the three pieces of session state
        // that AutoHost actually sets: selfHostedUrl, rspUUID, done. Same precedence as
        // sendResourcePack() uses, so what we report here is what clients will get.
        String selfUrl = AutoHost.getSelfHostedUrl();
        String rspUuid = AutoHost.getRspUUID();
        boolean done = AutoHost.isDone();
        String activePath;
        String activeUrl;
        if (selfUrl != null) {
            activePath = "&aSELF-HOSTED";
            activeUrl = selfUrl;
        } else if (rspUuid != null) {
            activePath = "&aREMOTE (magmaguy.com)";
            activeUrl = MagmaguyRspClient.BASE_URL + rspUuid;
        } else if (!done) {
            activePath = "&e(not yet ready — still mixing/uploading)";
            activeUrl = null;
        } else {
            activePath = "&c(none — hosting disabled or failed)";
            activeUrl = null;
        }
        Logger.sendMessage(sender, "&7Active delivery: " + activePath);
        if (activeUrl != null) {
            Logger.sendMessage(sender, "&7  URL: &f" + activeUrl);
        }
        if (rspUuid != null) {
            Logger.sendMessage(sender, "&7  Remote session UUID: &f" + rspUuid);
        }

        // Diagnostic detail for the self-host sanity-check chain. Even when remote
        // is currently active, this shows WHY self-host was skipped (or what the
        // resolved host looks like for the next initialize() retry).
        String resolvedHost = AutoHost.currentResolvedHost();
        Logger.sendMessage(sender, "&7Resolved external host: &f"
                + (resolvedHost == null ? "&c(null)" : resolvedHost));
        Optional<String> publicIpCache = AutoHost.getCachedPublicIp();
        String publicIpDisplay;
        if (publicIpCache == null) {
            publicIpDisplay = "&8(not detected yet — set selfHostExternalHost to skip auto-detect)";
        } else if (publicIpCache.isEmpty()) {
            publicIpDisplay = "&c(detect failed — ipify/AWS unreachable)";
        } else {
            publicIpDisplay = "&a" + publicIpCache.get();
        }
        Logger.sendMessage(sender, "&7Public IP (auto-detected): " + publicIpDisplay);
        String externalHostCfg = DefaultConfig.getSelfHostExternalHost();
        if (externalHostCfg != null && !externalHostCfg.isBlank()) {
            Logger.sendMessage(sender, "&7selfHostExternalHost (config): &f" + externalHostCfg);
        }
        int selfPort = DefaultConfig.getSelfHostPort();
        Logger.sendMessage(sender, "&7selfHostPort: &f"
                + (selfPort < 0 ? "auto (MC port + " + DefaultConfig.getNetworkHttpOffset() + ")" : String.valueOf(selfPort)));
        Logger.sendMessage(sender, "");

        // ---------- Proxy deployment ----------
        Logger.sendMessage(sender, "&8&m----- &6Proxy deployment &8&m-----");
        Logger.sendMessage(sender, "&7Network proxy jar: &fResourcePackManager.jar");
        Logger.sendMessage(sender, "&7Use the same jar on Bukkit/Paper, Velocity, and BungeeCord/Waterfall.");
        Logger.sendMessage(sender, "&7If running a network, copy ResourcePackManager.jar to the proxy's plugins/ folder.");
        Logger.sendMessage(sender, "");

        // ---------- Integrations ----------
        Logger.sendMessage(sender, "&8&m----- &5Integrations &8&m-----");
        Logger.sendMessage(sender, "&7Floodgate plugin: " + bool(Bukkit.getPluginManager().isPluginEnabled("floodgate")));
        Logger.sendMessage(sender, "&7Geyser-Spigot plugin: " + bool(Bukkit.getPluginManager().isPluginEnabled("Geyser-Spigot")));
        Logger.sendMessage(sender, "&7Online players: &f" + Bukkit.getOnlinePlayers().size());

        Logger.sendMessage(sender, "&8&m-------------------------");
    }

    // ---------- Formatting helpers ----------

    /** Pretty boolean: green ✓ on true, red ✗ on false. Keeps the dump scannable. */
    private static String bool(boolean v) {
        return v ? "&a✓ yes" : "&c✗ no";
    }

    /**
     * Compact byte-count formatter. Uses 1024-based units, two-decimal precision
     * for MiB+ so a 4.1 MB pack reads "4.13 MiB" rather than "4220544 bytes."
     */
    private static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KiB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.2f MiB", bytes / (1024.0 * 1024));
        return String.format("%.2f GiB", bytes / (1024.0 * 1024 * 1024));
    }

    /**
     * Mask all but the last 4 chars of the network key. The key is a UUID-shaped
     * shared secret between backends and proxy — full-key display in console
     * scrollback is a leak vector if the operator screen-shares this status
     * output. Last 4 chars are enough to confirm "yes I have the same key on
     * the proxy" without exposing the full secret.
     */
    private static String maskKey(String key) {
        if (key == null || key.length() < 8) return "********";
        return "********-****-****-****-************" + key.substring(key.length() - 4);
    }
}
