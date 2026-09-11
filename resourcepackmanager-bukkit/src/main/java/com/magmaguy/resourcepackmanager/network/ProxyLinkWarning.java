package com.magmaguy.resourcepackmanager.network;

import com.magmaguy.magmacore.util.ChatColorConverter;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.config.DataConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.regex.Pattern;

/**
 * Warns operators when this backend has a Bedrock stack (Geyser or Floodgate)
 * behind a proxy that is not running ResourcePackManager.
 *
 * <p>That combination fails silently: the Bedrock proxy handshake lives on the
 * proxy, so without RSPM there the backend can never link, and Bedrock players
 * receive no merged pack. The generic unkeyed line in {@link NetworkMode}
 * covers the plain Java case, but when Geyser or Floodgate is present the
 * stakes are Bedrock-specific and worth calling out explicitly and repeatedly
 * — this class is that dedicated, robust surface.</p>
 */
public final class ProxyLinkWarning {

    // Floodgate's default Bedrock username convention: a '.' prefix and a
    // 4-digit suffix. Java names can't start with a dot, so this is a
    // plugin-free Bedrock signal that survives proxy-only Floodgate, where no
    // Bedrock plugin exists on the backend at all.
    private static final Pattern BEDROCK_NAME_PATTERN = Pattern.compile("^\\..*\\d{4}$");

    private ProxyLinkWarning() {
    }

    /**
     * True when this backend is proxied, unkeyed (so the proxy is missing RSPM
     * or is unsupported), and there is evidence Bedrock is in play — either a
     * Bedrock stack installed here, or a Bedrock player already observed
     * arriving through the proxy (the proxy-only-Floodgate case). This is the
     * exact condition under which Bedrock features break with no other symptom.
     */
    public static boolean bedrockProxyLinkMissing() {
        if (!NetworkMode.isActive()) return false;
        if (NetworkMode.getKeySource() != NetworkMode.KeySource.NONE) return false;
        return hasBedrockStack() || DataConfig.getBedrockSeenBehindUnlinkedProxy();
    }

    private static boolean hasBedrockStack() {
        return Bukkit.getPluginManager().getPlugin("Geyser-Spigot") != null
                || Bukkit.getPluginManager().getPlugin("floodgate") != null;
    }

    /**
     * Best-effort, plugin-free Bedrock detection for a connecting player, so
     * proxy-only Floodgate (no Bedrock plugin on the backend) is still caught.
     * Two signals reach the backend through the forwarded connection: the
     * Floodgate synthetic UUID (its most-significant bits are zero, which a
     * real Mojang UUID never is) and the dotted username convention.
     */
    public static boolean looksBedrock(Player player) {
        if (player == null) return false;
        if (player.getUniqueId().getMostSignificantBits() == 0L) return true;
        String name = player.getName();
        return name != null && BEDROCK_NAME_PATTERN.matcher(name).matches();
    }

    /**
     * Records that a Bedrock player arrived while this backend was proxied and
     * unkeyed. Latches the observation (persisted) so later boots warn too.
     */
    public static void noteBedrockBehindUnlinkedProxy() {
        DataConfig.setBedrockSeenBehindUnlinkedProxy(true);
    }

    /**
     * Loud multi-line console banner. Emitted at boot from the unkeyed branch
     * and safe to re-emit; it names the Bedrock consequence and the fix.
     */
    public static void warnConsole() {
        Logger.warn("=====================================================================");
        Logger.warn("⚠  BEDROCK SETUP INCOMPLETE — the proxy is missing ResourcePackManager.");
        Logger.warn("⚠  " + bedrockEvidenceLine());
        Logger.warn("⚠  No network key has arrived, which means the proxy is not running RSPM.");
        Logger.warn("⚠  Effect: Bedrock players will NOT receive the merged resource pack and");
        Logger.warn("⚠          the Bedrock proxy handshake cannot complete until this is fixed.");
        Logger.warn("⚠  Fix: install ResourcePackManager on the proxy (Velocity and BungeeCord");
        Logger.warn("⚠       use the same jar — a copy is staged for you under");
        Logger.warn("⚠       plugins/ResourcePackManager/proxy-extension/) and restart the proxy.");
        Logger.warn("⚠  Then run '/rspm status' on both sides — the key fingerprints must match.");
        Logger.warn("=====================================================================");
    }

    /**
     * Operator-facing chat warning. Sent to online ops at boot and whenever an
     * op or a Bedrock player joins while the condition holds, so it never
     * depends on someone having watched the startup console.
     */
    public static void warnPlayer(Player player) {
        player.sendMessage("");
        player.sendMessage(ChatColorConverter.convert("&8&m----------------------------------------"));
        player.sendMessage(ChatColorConverter.convert("&c&l⚠ ResourcePackManager missing on the proxy"));
        player.sendMessage(ChatColorConverter.convert("&8&m----------------------------------------"));
        player.sendMessage(ChatColorConverter.convert("&e" + bedrockEvidenceLine()));
        player.sendMessage(ChatColorConverter.convert(
                "&eThe proxy is not running ResourcePackManager, so Bedrock players get no pack."));
        player.sendMessage(ChatColorConverter.convert(
                "&7Install RSPM on the proxy (same jar for Velocity/BungeeCord — a copy"));
        player.sendMessage(ChatColorConverter.convert(
                "&7is staged under plugins/ResourcePackManager/proxy-extension/) and restart it."));
        player.sendMessage(ChatColorConverter.convert("&7Then compare &f/rspm status&7 on both sides."));
        player.sendMessage(ChatColorConverter.convert("&8&m----------------------------------------"));
        player.sendMessage("");
    }

    /**
     * One line describing why we believe Bedrock is in play, worded correctly
     * for whichever topology triggered the warning: a Bedrock stack installed
     * on this backend, or a Bedrock player seen arriving through the proxy with
     * no backend Bedrock plugin at all (proxy-only Floodgate).
     */
    private static String bedrockEvidenceLine() {
        boolean geyser = Bukkit.getPluginManager().getPlugin("Geyser-Spigot") != null;
        boolean floodgate = Bukkit.getPluginManager().getPlugin("floodgate") != null;
        if (geyser && floodgate) return "This backend has Geyser and Floodgate and sits behind a proxy.";
        if (geyser) return "This backend has Geyser and sits behind a proxy.";
        if (floodgate) return "This backend has Floodgate and sits behind a proxy.";
        return "A Bedrock player reached this backend through the proxy (Floodgate is on the proxy only).";
    }
}
