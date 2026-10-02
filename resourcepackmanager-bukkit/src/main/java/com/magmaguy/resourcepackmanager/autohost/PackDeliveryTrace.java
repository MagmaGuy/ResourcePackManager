package com.magmaguy.resourcepackmanager.autohost;

import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.utils.RSPLogger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-connection record of how the Java pack reached one player, printed when it did not.
 * <p>
 * A client answers a pack push with ACCEPTED, DOWNLOADED and SUCCESSFULLY_LOADED, or with a
 * failure. It says nothing at all while the pack prompt is open or while its download is stuck,
 * and RSPM used to report only failures, so "the pack never even tried to load" left nothing in
 * the console. This keeps a short timeline for each connection and prints it with the likely
 * cause when delivery is declined, fails, stalls or never starts. Successful deliveries stay
 * quiet unless they were slow or needed help.
 * <p>
 * Minecraft downloads server packs one at a time for the whole game session, without a
 * timeout, and leaving a server does not cancel a download in progress. A stalled earlier
 * download therefore holds back every later pack without showing any progress, which is the
 * "accepted but never downloaded" case below.
 */
final class PackDeliveryTrace {
    private static final long NO_SEND_WARNING_NANOS = 30_000_000_000L;
    private static final int[] STALL_CHECKPOINT_SECONDS = {45, 180, 600};
    private static final long QUIET_SUCCESS_NANOS = 15_000_000_000L;
    private static final long INSTANT_DECLINE_NANOS = 2_000_000_000L;
    private static final long PROBE_INTERVAL_NANOS = 60_000_000_000L;
    private static final int PROBE_SAMPLE_BYTES = 256 * 1024;
    private static final long CHECK_PERIOD_TICKS = 100L;
    private static final int MAX_EVENTS = 60;
    private static final int KEPT_LEADING_EVENTS = 4;

    private static final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private static BukkitTask checker = null;
    private static volatile long lastProbeAt = 0L;

    private PackDeliveryTrace() {
    }

    private static final class Session {
        private final String name;
        private final long joinedAt = System.nanoTime();
        private final List<String> events = new ArrayList<>();
        private long lastSendAt = -1L;
        private String lastStatus = null;
        private int sendsSinceResult = 0;
        private int stallStage = 0;
        private boolean finished = false;
        private boolean noteworthy = false;
        private boolean noSendWarned = false;
        private long acceptedAt = -1L;
        private long downloadedAt = -1L;

        private Session(String name) {
            this.name = name;
        }

        private synchronized void add(String event) {
            String line = "+" + seconds(System.nanoTime() - joinedAt) + " " + event;
            // A long session collects a few lines per reload; keep the join context and the recent past.
            if (events.size() >= MAX_EVENTS) events.remove(KEPT_LEADING_EVENTS);
            events.add(line);
            RSPLogger.detail("Pack delivery | " + name + " | " + line);
        }

        private synchronized List<String> timeline() {
            return new ArrayList<>(events);
        }
    }

    /** Starts the periodic stall checks for this plugin lifecycle. */
    static synchronized void start() {
        if (ResourcePackManager.plugin == null || !ResourcePackManager.plugin.isEnabled()) return;
        if (checker != null) checker.cancel();
        checker = Bukkit.getScheduler().runTaskTimer(ResourcePackManager.plugin,
                PackDeliveryTrace::checkSessions, CHECK_PERIOD_TICKS, CHECK_PERIOD_TICKS);
    }

    /** Records that RSPM is stopping; timelines survive a reload so the next lifecycle continues them. */
    static synchronized void lifecycleStopped() {
        if (checker != null) {
            checker.cancel();
            checker = null;
        }
        for (Session session : sessions.values()) {
            if (!session.finished) session.add("RSPM stopped or reloaded; pending sends were cancelled");
        }
    }

    static void joined(Player player, String packState) {
        Session session = new Session(player.getName());
        sessions.put(player.getUniqueId(), session);
        session.add("joined: " + describeConnection(player));
        session.add("pack: " + packState);
    }

    static void event(Player player, String event) {
        Session session = sessions.get(player.getUniqueId());
        if (session != null) session.add(event);
    }

    static void sent(Player player, String cause, String detail) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) return;
        synchronized (session) {
            if (session.lastSendAt >= 0 || !"join".equals(cause)) session.noteworthy = true;
            session.lastSendAt = System.nanoTime();
            session.lastStatus = null;
            session.stallStage = 0;
            session.sendsSinceResult++;
            session.finished = false;
        }
        session.add("sent (" + cause + "): " + detail);
    }

    static void notSent(Player player, String cause, String reason) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) return;
        session.noteworthy = true;
        session.add("not sent (" + cause + "): " + reason);
    }

    static void status(Player player, String status, UUID packId, boolean ours) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) return;
        if (!ours) {
            session.add("client: " + status + " for another plugin's pack " + packId);
            return;
        }
        long now = System.nanoTime();
        String previous;
        long sendAt;
        synchronized (session) {
            previous = session.lastStatus;
            sendAt = session.lastSendAt;
            session.lastStatus = status;
            if (status.equals("ACCEPTED")) session.acceptedAt = now;
            if (status.equals("DOWNLOADED")) session.downloadedAt = now;
        }
        session.add("client: " + status
                + (sendAt >= 0 ? " (" + seconds(now - sendAt) + " after the last send)" : ""));
        switch (status) {
            case "SUCCESSFULLY_LOADED" -> succeeded(player, session, sendAt, now);
            case "DECLINED" -> {
                finish(session);
                boolean instant = previous == null && sendAt >= 0 && now - sendAt <= INSTANT_DECLINE_NANOS;
                report(session, player.getName() + " declined the resource pack.", instant
                        ? List.of("The client declined instantly without asking, so its server-list entry has Server Resource Packs set to Disabled.",
                        "Fix on the player's side: Multiplayer, select this server, Edit, set Server Resource Packs to Enabled or Prompt, then rejoin.")
                        : List.of("The player answered No to the pack prompt. Minecraft now remembers Disabled for that server entry.",
                        "Fix on the player's side: Multiplayer, select this server, Edit, set Server Resource Packs to Enabled or Prompt, then rejoin."));
            }
            case "FAILED_DOWNLOAD", "DISCARDED" -> session.noteworthy = true;
            default -> {
            }
        }
    }

    /** A delivery problem AutoHost already announced; adds the timeline and a hosting check. */
    static void failed(Player player, String headline, List<String> hints, String url) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) return;
        finish(session);
        report(session, headline, hints);
        probe(url);
    }

    static void quit(Player player) {
        Session session = sessions.remove(player.getUniqueId());
        if (session == null || session.finished) return;
        long now = System.nanoTime();
        if (session.lastSendAt < 0) {
            if (now - session.joinedAt < NO_SEND_WARNING_NANOS && !session.noteworthy) return;
            session.add("left without a resource pack ever being sent");
        } else {
            session.add("left " + seconds(now - session.lastSendAt) + " after the last send; last client response: "
                    + (session.lastStatus == null ? "none" : session.lastStatus));
        }
        report(session, player.getName() + " left before the resource pack finished loading.",
                hintsFor(session));
    }

    private static void succeeded(Player player, Session session, long sendAt, long now) {
        boolean slow = now - session.joinedAt >= QUIET_SUCCESS_NANOS;
        finish(session);
        if (!slow && !session.noteworthy) return;
        StringBuilder line = new StringBuilder("Resource pack loaded for ").append(player.getName())
                .append(" ").append(seconds(now - session.joinedAt)).append(" after joining");
        if (sendAt >= 0) {
            line.append(" (sent +").append(seconds(sendAt - session.joinedAt));
            if (session.acceptedAt >= 0) line.append(", accepted +").append(seconds(session.acceptedAt - session.joinedAt));
            if (session.downloadedAt >= 0) line.append(", downloaded +").append(seconds(session.downloadedAt - session.joinedAt));
            line.append(")");
        }
        Logger.info(line + ".");
        if (session.noteworthy) {
            for (String event : session.timeline()) Logger.info("  " + event);
        }
    }

    private static void finish(Session session) {
        synchronized (session) {
            session.finished = true;
            session.sendsSinceResult = 0;
        }
    }

    private static void checkSessions() {
        long now = System.nanoTime();
        for (Map.Entry<UUID, Session> entry : sessions.entrySet()) {
            Session session = entry.getValue();
            if (session.finished) continue;
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) continue;
            if (session.lastSendAt < 0) {
                if (!session.noSendWarned && now - session.joinedAt >= NO_SEND_WARNING_NANOS) {
                    session.noSendWarned = true;
                    session.add("pack state now: " + AutoHost.packStateSummary());
                    report(session, "No resource pack has been sent to " + player.getName() + " "
                            + seconds(now - session.joinedAt) + " after they joined.", List.of(
                            "RSPM has not pushed the pack to this connection. The timeline shows the last reason it was held back."));
                }
                continue;
            }
            if (session.stallStage >= STALL_CHECKPOINT_SECONDS.length) continue;
            long waited = now - session.lastSendAt;
            if (waited < STALL_CHECKPOINT_SECONDS[session.stallStage] * 1_000_000_000L) continue;
            session.stallStage++;
            session.noteworthy = true;
            session.add("still waiting; ping " + player.getPing() + " ms");
            report(session, player.getName() + " has not loaded the resource pack " + seconds(waited)
                    + " after it was sent (last client response: "
                    + (session.lastStatus == null ? "none" : session.lastStatus) + ").", hintsFor(session));
            if (session.stallStage == 1) probe(AutoHost.currentPackUrl());
        }
    }

    private static List<String> hintsFor(Session session) {
        List<String> hints = new ArrayList<>();
        if (session.lastSendAt < 0) {
            hints.add("RSPM never pushed the pack to this connection. The timeline shows the last reason it was held back.");
            return hints;
        }
        String status = session.lastStatus;
        if (status == null) {
            hints.add("The client never answered. Either the pack prompt is still open on the player's screen,"
                    + " or the client did not act on the request. A Disabled server-list entry would have answered DECLINED instead.");
        } else if (status.equals("ACCEPTED")) {
            hints.add("The client accepted the pack but has not finished downloading it. Minecraft downloads server packs"
                    + " one at a time per game session and never times out, so a slow or stalled earlier download,"
                    + " even one started on another server, holds this one back without showing any progress.");
            hints.add("Fully restarting Minecraft clears a stalled download. If it keeps happening after a restart,"
                    + " collect the client's logs/latest.log and .minecraft/downloads/log.json.");
            if (session.sendsSinceResult > 1) {
                hints.add("RSPM sent the pack " + session.sendsSinceResult + " times while the client was still working on it;"
                        + " each send queues another download behind the first.");
            }
        } else if (status.equals("DOWNLOADED")) {
            hints.add("The client downloaded the pack and is still applying it. A resource reload this slow points at the"
                    + " client's hardware or mods; check the client's logs/latest.log.");
        } else {
            hints.add("The last client response was " + status + ".");
        }
        return hints;
    }

    private static void report(Session session, String headline, List<String> hints) {
        session.noteworthy = true;
        Logger.warn("Pack delivery problem: " + headline);
        for (String event : session.timeline()) Logger.warn("  " + event);
        for (String hint : hints) Logger.warn("  -> " + hint);
    }

    /** Fetches the start of the pack from this server, at most once a minute, to separate hosting from client problems. */
    private static void probe(String url) {
        if (url == null || url.isBlank()) return;
        long now = System.nanoTime();
        if (lastProbeAt != 0L && now - lastProbeAt < PROBE_INTERVAL_NANOS) return;
        lastProbeAt = now;
        if (ResourcePackManager.plugin == null || !ResourcePackManager.plugin.isEnabled()) return;
        Bukkit.getScheduler().runTaskAsynchronously(ResourcePackManager.plugin,
                () -> Logger.warn("  -> Hosting check from this server: " + probeResult(url)));
    }

    static String probeResult(String url) {
        long start = System.nanoTime();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            connection.setRequestProperty("Range", "bytes=0-" + (PROBE_SAMPLE_BYTES - 1));
            connection.setRequestProperty("User-Agent", "ResourcePackManager delivery check");
            int code = connection.getResponseCode();
            long firstResponse = System.nanoTime() - start;
            long size = totalSize(connection);
            if (code != 200 && code != 206) {
                return url + " answered HTTP " + code + " after " + millis(firstResponse) + ".";
            }
            long read = 0L;
            long readStart = System.nanoTime();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int count;
                while (read < PROBE_SAMPLE_BYTES && (count = input.read(buffer)) != -1) read += count;
            }
            long readTime = Math.max(1L, System.nanoTime() - readStart);
            return url + " answered HTTP " + code + (size >= 0 ? ", " + megabytes(size) : ", size unknown")
                    + ", first response after " + millis(firstResponse) + ", " + (read / 1024) + " KB read at "
                    + String.format(Locale.ROOT, "%.0f KB/s", read / 1024.0 / (readTime / 1_000_000_000.0))
                    + ". The pack is reachable from the server, so a stall is on the client's side or its connection.";
        } catch (IOException | IllegalArgumentException exception) {
            return url + " could not be fetched after " + millis(System.nanoTime() - start) + ": " + exception
                    + ". Clients will not be able to download it either.";
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static long totalSize(HttpURLConnection connection) {
        String range = connection.getHeaderField("Content-Range");
        if (range != null) {
            int slash = range.lastIndexOf('/');
            if (slash >= 0) {
                try {
                    return Long.parseLong(range.substring(slash + 1).trim());
                } catch (NumberFormatException ignored) {
                    // Unknown total ("*"); fall back to Content-Length.
                }
            }
        }
        return connection.getContentLengthLong();
    }

    private static String describeConnection(Player player) {
        StringBuilder description = new StringBuilder();
        InetSocketAddress address = player.getAddress();
        description.append(addressClass(address == null ? null : address.getAddress()));
        Object brand = paperProperty(player, "getClientBrandName");
        if (brand != null) description.append(", client ").append(brand);
        Object protocol = paperProperty(player, "getProtocolVersion");
        if (protocol != null) description.append(", protocol ").append(protocol);
        description.append(", ping ").append(player.getPing()).append(" ms");
        return description.toString();
    }

    /** The kind of address the player connects from, never the address itself. */
    static String addressClass(InetAddress address) {
        if (address == null) return "unknown address";
        if (address.isLoopbackAddress()) return "same machine as the server (loopback)";
        if (address.isSiteLocalAddress() || address.isLinkLocalAddress()) return "local network";
        byte[] bytes = address.getAddress();
        if (bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc) return "local network";
        return "internet";
    }

    /** Paper-only accessors, looked up by name so Spigot servers simply omit them. */
    private static Object paperProperty(Player player, String methodName) {
        try {
            Method method = player.getClass().getMethod(methodName);
            return method.invoke(player);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static String seconds(long nanos) {
        return String.format(Locale.ROOT, "%.1fs", nanos / 1_000_000_000.0);
    }

    private static String millis(long nanos) {
        return (nanos / 1_000_000L) + " ms";
    }

    static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
    }
}
