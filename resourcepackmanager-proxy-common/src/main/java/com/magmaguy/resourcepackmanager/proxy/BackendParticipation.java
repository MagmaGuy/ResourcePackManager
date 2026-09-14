package com.magmaguy.resourcepackmanager.proxy;

import com.magmaguy.resourcepackmanager.http.PackHttpServer;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CancellationException;

/**
 * Discovers RSPM on registered servers without making pack synchronization wait
 * for servers that do not run it. The existing updater challenge identifies the
 * service even when it has no network key or Bedrock output. It is a discovery
 * hint, never authorization: pack validation and authenticated updates still run
 * in NetworkSync. Failed probes do not establish absence or remove participants.
 */
final class BackendParticipation {
    private static final int MAX_PROBES = 2;
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DISCOVERY_INTERVAL = Duration.ofMinutes(1);

    private final HttpClient http;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private boolean stopped;

    BackendParticipation(HttpClient http) {
        this.http = http;
    }

    // Equality deliberately excludes registry refresh timestamps and descriptive text.
    record Endpoint(String host, int port, String backendId) {}

    private static final class Entry {
        final Endpoint endpoint;
        boolean confirmed;
        Instant nextProbe = Instant.EPOCH;
        CompletableFuture<HttpResponse<Void>> probe;

        Entry(Endpoint endpoint) { this.endpoint = endpoint; }

        void cancel() {
            if (probe != null) probe.cancel(true);
            probe = null;
        }
    }

    /** Called by the single sync owner; all network probes return immediately. */
    synchronized void refresh(Map<String, Endpoint> endpoints, Set<String> knownParticipants, Instant now) {
        if (stopped) return;
        entries.entrySet().removeIf(item -> {
            if (endpoints.containsKey(item.getKey())) return false;
            item.getValue().cancel();
            return true;
        });
        for (var item : endpoints.entrySet()) {
            Entry entry = entries.get(item.getKey());
            boolean firstObservation = entry == null;
            if (entry == null || !entry.endpoint.equals(item.getValue())) {
                if (entry != null) entry.cancel();
                entry = new Entry(item.getValue());
                entries.put(item.getKey(), entry);
            }
            if (entry.endpoint.backendId() != null
                    || (firstObservation && knownParticipants.contains(item.getKey()))) {
                entry.confirmed = true;
                entry.cancel();
            } else if (entry.probe != null && entry.probe.isDone()) {
                try {
                    HttpResponse<Void> response = entry.probe.join();
                    entry.confirmed = response.statusCode() == 401
                            && response.headers().allValues("WWW-Authenticate").stream()
                            .anyMatch(PackHttpServer.EXECUTABLE_UPDATE_AUTH_CHALLENGE::equals);
                } catch (CompletionException | CancellationException ignored) {
                    // Unconfirmed is normal. Detailed fetch failures apply only after discovery.
                }
                entry.probe = null;
                entry.nextProbe = now.plus(DISCOVERY_INTERVAL);
            }
        }
        int running = (int) entries.values().stream().filter(entry -> entry.probe != null).count();
        // New/oldest-due discoveries go first, so a large registry cannot starve
        // its last entries behind recurring failures at the front of the list.
        for (Entry entry : entries.values().stream()
                .sorted(Comparator.comparing(candidate -> candidate.nextProbe)).toList()) {
            if (running >= MAX_PROBES) break;
            if (entry.confirmed || entry.probe != null || now.isBefore(entry.nextProbe)) continue;
            try {
                URI uri = new URI("http", null, entry.endpoint.host(), entry.endpoint.port(),
                        PackHttpServer.EXECUTABLE_UPDATE_PATH, null, null);
                HttpRequest request = HttpRequest.newBuilder(uri).timeout(PROBE_TIMEOUT)
                        .method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
                entry.probe = http.sendAsync(request, HttpResponse.BodyHandlers.discarding());
                running++;
            } catch (IllegalArgumentException | URISyntaxException failure) {
                entry.nextProbe = now.plus(DISCOVERY_INTERVAL);
            }
        }
    }

    synchronized boolean isParticipant(String name) {
        Entry entry = entries.get(name);
        return entry != null && entry.confirmed;
    }

    record Snapshot(Set<String> participants, Map<String, String> discovery) {}

    synchronized Snapshot snapshot() {
        Set<String> participants = new HashSet<>();
        Map<String, String> discovery = new HashMap<>();
        entries.forEach((name, entry) -> {
            if (entry.confirmed) participants.add(name);
            else discovery.put(name, entry.probe != null ? "checking for RSPM"
                    : entry.nextProbe.equals(Instant.EPOCH) ? "waiting for discovery"
                    : "RSPM not confirmed; next discovery after " + entry.nextProbe);
        });
        return new Snapshot(Set.copyOf(participants), Map.copyOf(discovery));
    }

    synchronized void start() { stopped = false; }

    synchronized void stop() {
        stopped = true;
        entries.values().forEach(Entry::cancel);
        entries.clear();
    }
}
