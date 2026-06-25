package com.magmaguy.resourcepackmanager.proxy;

import org.junit.jupiter.api.Test;

import com.magmaguy.resourcepackmanager.http.MagmaguyRspClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the pure static helpers extracted from {@link NetworkSync}.
 * Kept package-private specifically so they're testable without standing up
 * the scheduler or HTTP client.
 */
class NetworkSyncTest {

    @Test
    void sanitizeBackendName_passesThroughSafeCharacters() {
        assertEquals("lobby-1", NetworkSync.sanitizeBackendName("lobby-1"));
        assertEquals("survival.world", NetworkSync.sanitizeBackendName("survival.world"));
        assertEquals("server_42", NetworkSync.sanitizeBackendName("server_42"));
    }

    @Test
    void sanitizeBackendName_replacesPathSeparators() {
        // A weird Velocity server name with a slash must NOT be able to escape
        // its own inbox directory.
        assertEquals("lobby_world", NetworkSync.sanitizeBackendName("lobby/world"));
        assertEquals("a_b_c", NetworkSync.sanitizeBackendName("a\\b/c"));
    }

    @Test
    void sanitizeBackendName_replacesSpacesAndSymbols() {
        assertEquals("my_server__", NetworkSync.sanitizeBackendName("my server!?"));
    }

    @Test
    void sanitizeBackendName_handlesNullAndEmpty() {
        assertEquals("_", NetworkSync.sanitizeBackendName(null));
        assertEquals("_", NetworkSync.sanitizeBackendName(""));
    }

    @Test
    void resolveBackendHttpEndpoint_prefersMatchingAnnouncement() {
        BackendListProvider.Backend backend = new BackendListProvider.Backend("lobby", "140.238.249.93", 6699);
        MagmaguyRspClient.BedrockEndpoint endpoint = endpoint("backend-a", "140.238.249.93", null, 6699, 25569);

        NetworkSync.ResolvedBackendEndpoint resolved =
                NetworkSync.resolveBackendHttpEndpoint(backend, 1, List.of(endpoint));

        assertEquals("140.238.249.93", resolved.host());
        assertEquals(25569, resolved.port());
        assertEquals("announced by backend backend-a", resolved.source());
    }

    @Test
    void resolveBackendHttpEndpoint_usesBackendHostEvenWhenAnnouncementHasPublicHost() {
        BackendListProvider.Backend backend = new BackendListProvider.Backend("lobby", "127.0.0.1", 6699);
        MagmaguyRspClient.BedrockEndpoint endpoint = endpoint("backend-a", "203.0.113.20", null, 6699, 25569);

        NetworkSync.ResolvedBackendEndpoint resolved =
                NetworkSync.resolveBackendHttpEndpoint(backend, 1, List.of(endpoint));

        assertEquals("127.0.0.1", resolved.host());
        assertEquals(25569, resolved.port());
        assertEquals("announced by backend backend-a (unique MC port match)", resolved.source());
    }

    @Test
    void resolveBackendHttpEndpoint_matchesForwardedIpv4SourceIp() {
        BackendListProvider.Backend backend = new BackendListProvider.Backend("lobby", "127.0.0.1", 6699);
        MagmaguyRspClient.BedrockEndpoint endpoint = endpoint("backend-a", null, "::ffff:127.0.0.1", 6699, 25569);

        NetworkSync.ResolvedBackendEndpoint resolved =
                NetworkSync.resolveBackendHttpEndpoint(backend, 1, List.of(endpoint));

        assertEquals(25569, resolved.port());
        assertEquals("announced by backend backend-a", resolved.source());
    }

    @Test
    void resolveBackendHttpEndpoint_fallsBackWhenAnnouncementsAreAmbiguous() {
        BackendListProvider.Backend backend = new BackendListProvider.Backend("lobby", "10.0.0.5", 6699);
        MagmaguyRspClient.BedrockEndpoint first = endpoint("backend-a", "203.0.113.20", null, 6699, 25569);
        MagmaguyRspClient.BedrockEndpoint second = endpoint("backend-b", "203.0.113.21", null, 6699, 25570);

        NetworkSync.ResolvedBackendEndpoint resolved =
                NetworkSync.resolveBackendHttpEndpoint(backend, 1, List.of(first, second));

        assertEquals("10.0.0.5", resolved.host());
        assertEquals(6700, resolved.port());
        assertEquals("mcPort + network-http-offset-v2 1", resolved.source());
    }

    private static MagmaguyRspClient.BedrockEndpoint endpoint(
            String backendId,
            String publicHost,
            String sourceIp,
            int mcPort,
            int httpPort) {
        return new MagmaguyRspClient.BedrockEndpoint(
                backendId, publicHost, sourceIp, mcPort, httpPort, "2026-06-22T00:00:00Z");
    }
}
