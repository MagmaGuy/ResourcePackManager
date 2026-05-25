package com.magmaguy.resourcepackmanager.proxy;

import org.junit.jupiter.api.Test;

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
}
