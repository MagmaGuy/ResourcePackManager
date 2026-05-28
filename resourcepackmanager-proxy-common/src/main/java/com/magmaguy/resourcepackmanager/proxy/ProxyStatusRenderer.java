package com.magmaguy.resourcepackmanager.proxy;

import com.magmaguy.resourcepackmanager.http.PackHttpServer;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Renders {@link NetworkSync.Snapshot} into a human-readable multi-line block
 * for {@code /rspm status} on the proxy side. Platform-neutral — Velocity and
 * Bungee invoke this with their own {@code Consumer<String>} that wraps each
 * line into the appropriate platform's message API.
 *
 * <p>The output mirrors the backend's {@code /rspm status} block structure
 * (sections separated by dashed dividers) so operators see consistent layout
 * on both sides of a network. Color codes are MagmaCore-style {@code &}-prefix
 * legacy section-symbol formatting; platform adapters are responsible for
 * translating these into whatever the platform's chat API actually renders.</p>
 *
 * <h2>Threading</h2>
 * Reads a {@link NetworkSync.Snapshot} that was captured atomically by
 * {@code NetworkSync.snapshot()}. The renderer itself does no I/O and no
 * locking — safe to call from any thread including the command dispatch thread.
 */
public final class ProxyStatusRenderer {

    private final String pluginVersion;
    private final String networkKey;
    private final NetworkSync.Snapshot snapshot;
    private final boolean geyserDetected;
    private final boolean floodgateDetected;
    private final File geyserPluginDir;

    public ProxyStatusRenderer(String pluginVersion,
                               String networkKey,
                               NetworkSync.Snapshot snapshot,
                               boolean geyserDetected,
                               boolean floodgateDetected,
                               File geyserPluginDir) {
        this.pluginVersion = pluginVersion;
        this.networkKey = networkKey;
        this.snapshot = snapshot;
        this.geyserDetected = geyserDetected;
        this.floodgateDetected = floodgateDetected;
        this.geyserPluginDir = geyserPluginDir;
    }

    /**
     * Emit the status block. Each call to {@code line.accept(...)} is one
     * message — platform adapters typically render each as a separate chat line.
     */
    public void render(Consumer<String> line) {
        line.accept("&8&m----- &6&lRSPM Status (proxy) &8&m-----");
        line.accept("&7Version: &f" + pluginVersion);
        line.accept("&7Network key: &f" + maskKey(networkKey));
        line.accept("");

        // ---------- Backends NetworkSync sees ----------
        line.accept("&8&m----- &eBackends &8&m-----");
        List<BackendListProvider.Backend> backends = snapshot.backends();
        if (backends.isEmpty()) {
            line.accept("&c⚠ NetworkSync sees ZERO backends.");
            line.accept("&c  The proxy plugin manager reports no registered servers. Causes:");
            line.accept("&c    • velocity.toml / bungeecord config.yml has no [servers] populated yet.");
            line.accept("&c    • Proxy is rejecting server registrations.");
            line.accept("&c  Fix: run `/server` and confirm at least one backend is listed.");
        } else {
            line.accept("&7Count: &f" + backends.size());
            for (BackendListProvider.Backend b : backends) {
                String key = sanitizeBackendName(b.name());
                int httpPort = b.mcPort() + snapshot.networkHttpOffset();
                line.accept("&7  • &f" + b.name() + " &8(MC " + b.host() + ":" + b.mcPort()
                        + " → HTTP " + b.host() + ":" + httpPort + ")");
                NetworkSync.FetchOutcome zipOutcome = snapshot.fetchOutcomes()
                        .get(key + ":" + PackHttpServer.BEDROCK_PACK_PATH);
                NetworkSync.FetchOutcome mapOutcome = snapshot.fetchOutcomes()
                        .get(key + ":" + PackHttpServer.GEYSER_MAPPINGS_PATH);
                line.accept("&7      /bedrock.zip:   " + describeOutcome(zipOutcome));
                line.accept("&7      /mappings.json: " + describeOutcome(mapOutcome));
            }
        }
        line.accept("");

        // ---------- Bedrock relay activity ----------
        // Only show the relay block if at least one relay-* outcome was
        // recorded this session — operators with a working dedicated-host
        // setup never use the relay and don't want a block claiming
        // "0 relay entries" cluttering their status output.
        java.util.List<java.util.Map.Entry<String, NetworkSync.FetchOutcome>> relayOutcomes =
                new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, NetworkSync.FetchOutcome> e : snapshot.fetchOutcomes().entrySet()) {
            if (e.getKey().startsWith("relay-")) relayOutcomes.add(e);
        }
        if (!relayOutcomes.isEmpty()) {
            line.accept("&8&m----- &9Bedrock relay (magmaguy.com) &8&m-----");
            line.accept("&7Used as a bridge when direct backend HTTP fetch fails");
            line.accept("&7(typical of shared/managed hosting where MC port is exposed");
            line.accept("&7but adjacent ports are firewalled).");
            // Aggregate per relay backend id (strip "relay-" prefix + path suffix).
            java.util.Map<String, java.util.Map<String, NetworkSync.FetchOutcome>> byBackend =
                    new java.util.TreeMap<>();
            for (java.util.Map.Entry<String, NetworkSync.FetchOutcome> e : relayOutcomes) {
                String key = e.getKey().substring("relay-".length());
                int colon = key.lastIndexOf(':');
                String backendId = (colon > 0) ? key.substring(0, colon) : key;
                String kind = (colon > 0) ? key.substring(colon + 1) : "?";
                byBackend.computeIfAbsent(backendId, k -> new java.util.LinkedHashMap<>())
                        .put(kind, e.getValue());
            }
            for (java.util.Map.Entry<String, java.util.Map<String, NetworkSync.FetchOutcome>> e : byBackend.entrySet()) {
                line.accept("&7  • &fbackend-id &7" + e.getKey());
                for (java.util.Map.Entry<String, NetworkSync.FetchOutcome> kindEntry : e.getValue().entrySet()) {
                    line.accept("&7      " + kindEntry.getKey() + ": " + describeOutcome(kindEntry.getValue()));
                }
            }
            line.accept("");
        }

        // ---------- NetworkSync runtime state ----------
        line.accept("&8&m----- &dNetworkSync &8&m-----");
        line.accept("&7HTTP port offset: &f" + snapshot.networkHttpOffset()
                + " &8(applied as: HTTP port = backend MC port + offset)");
        if (snapshot.consecutiveEmptyPolls() > 0) {
            line.accept("&7Consecutive empty polls: &c" + snapshot.consecutiveEmptyPolls()
                    + (snapshot.unreachableWarningFired()
                        ? " &8(unreachable warning already fired)"
                        : ""));
        } else {
            line.accept("&7Consecutive empty polls: &a0 &8(healthy)");
        }
        File mergedZip = snapshot.mergedBedrockZip();
        File mergedMap = snapshot.mergedMappings();
        line.accept("&7Merged Bedrock pack on disk: " + bool(mergedZip != null)
                + (mergedZip != null ? " &8(" + humanBytes(mergedZip.length()) + ")" : ""));
        line.accept("&7Merged Geyser mappings on disk: " + bool(mergedMap != null)
                + (mergedMap != null ? " &8(" + humanBytes(mergedMap.length()) + ")" : ""));
        if (snapshot.currentMergedPack() != null) {
            MergedPack mp = snapshot.currentMergedPack();
            line.accept("&7Current MergedPack: &a✓ &7sha1 &f"
                    + (mp.sha1Hex() != null && mp.sha1Hex().length() >= 8
                        ? mp.sha1Hex().substring(0, 8)
                        : "(unknown)"));
        } else {
            line.accept("&7Current MergedPack: &c✗ &7(no pack registered with Geyser)");
        }
        line.accept("");

        // ---------- Geyser deploy state ----------
        line.accept("&8&m----- &bGeyser deploy &8&m-----");
        if (geyserPluginDir == null) {
            line.accept("&c⚠ Geyser plugin folder NOT detected. The pack won't be served to Bedrock");
            line.accept("&c  clients without Geyser running on this proxy. Install Geyser-Velocity");
            line.accept("&c  (or Geyser-BungeeCord) and restart.");
        } else {
            line.accept("&7Geyser plugin folder: &f" + geyserPluginDir.getAbsolutePath());
            File deployed = new File(new File(geyserPluginDir, "custom_mappings"),
                    "rspm_geyser_mappings.json");
            line.accept("&7Deployed mappings file: " + bool(deployed.isFile())
                    + (deployed.isFile() ? " &8(" + humanBytes(deployed.length()) + ")" : ""));
            if (deployed.isFile() && snapshot.currentMergedPack() == null) {
                // Mapping deployed but no current MergedPack → next proxy restart needed.
                line.accept("&e  ⓘ Mappings deployed but no current pack — restart the proxy");
                line.accept("&e    to register the custom items with Geyser (boot-frozen registry).");
            }
        }
        line.accept("");

        // ---------- Integrations ----------
        line.accept("&8&m----- &5Integrations &8&m-----");
        line.accept("&7Geyser plugin: " + bool(geyserDetected));
        line.accept("&7Floodgate plugin: " + bool(floodgateDetected));

        // ---------- Diagnostic summary ----------
        boolean somethingWrong = backends.isEmpty()
                || snapshot.currentMergedPack() == null
                || !geyserDetected
                || !floodgateDetected;
        if (somethingWrong) {
            line.accept("");
            line.accept("&8&m----- &c⚠ Diagnostic &8&m-----");
            if (backends.isEmpty()) {
                line.accept("&c• No backends registered with this proxy. Bedrock players cannot");
                line.accept("&c  receive a pack until at least one backend is added.");
            }
            if (snapshot.currentMergedPack() == null && !backends.isEmpty()) {
                line.accept("&c• Proxy has backends but no merged pack. Check fetch outcomes above");
                line.accept("&c  — most likely the backend HTTP port is unreachable from this proxy.");
                line.accept("&c  • CONNECT_FAILED → check velocity.toml addresses + firewall.");
                line.accept("&c  • NOT_FOUND_404  → run `/rspm status` on the backend; its Bedrock");
                line.accept("&c    diagnostic block will tell you why it's not producing a pack.");
            }
            if (!floodgateDetected) {
                line.accept("&c• Floodgate is not installed on this proxy. Bedrock players cannot");
                line.accept("&c  connect to the proxy at all without it.");
            }
            if (!geyserDetected) {
                line.accept("&c• Geyser is not installed on this proxy. The merged pack has no way");
                line.accept("&c  to reach Bedrock clients without Geyser.");
            }
        }
        line.accept("&8&m-------------------------");
    }

    // ---- helpers ----

    private static String describeOutcome(NetworkSync.FetchOutcome o) {
        if (o == null) return "&7(not yet attempted this session)";
        return switch (o.kind()) {
            case OK_200 -> "&a✓ 200 OK";
            case NOT_MODIFIED_304 -> "&a✓ 304 cached";
            case NOT_FOUND_404 -> "&e404 not yet produced &8(" + o.detail() + ")";
            case UNEXPECTED_STATUS -> "&cHTTP " + o.httpStatus() + " &8(" + o.detail() + ")";
            case CONNECT_FAILED -> "&cCONNECT_FAILED &8(" + o.detail() + ")";
            case OTHER_ERROR -> "&cERROR &8(" + o.detail() + ")";
        };
    }

    private static String bool(boolean v) {
        return v ? "&a✓ yes" : "&c✗ no";
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KiB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.2f MiB", bytes / (1024.0 * 1024));
        return String.format("%.2f GiB", bytes / (1024.0 * 1024 * 1024));
    }

    /** Mirrors NetworkSync.sanitizeBackendName so the keys line up. */
    private static String sanitizeBackendName(String raw) {
        if (raw == null || raw.isEmpty()) return "_";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    private static String maskKey(String key) {
        if (key == null || key.length() < 4) return "&c(unresolved)";
        return "&a********-****-****-****-************" + key.substring(key.length() - 4);
    }
}
