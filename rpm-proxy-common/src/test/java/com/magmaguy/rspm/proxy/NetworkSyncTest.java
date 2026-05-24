package com.magmaguy.rspm.proxy;

import com.magmaguy.rspm.http.MagmaguyRspClient.ManifestResult.Entry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure static helpers extracted from {@link NetworkSync}.
 * These exist as package-private statics specifically so they're testable
 * without having to mock the HTTP client or stand up the scheduler.
 */
class NetworkSyncTest {

    @Test
    void hasManifestChanged_sameSetSameShas_returnsFalse() {
        Map<String, String> previous = Map.of("uuid-a", "sha-a", "uuid-b", "sha-b");
        Map<String, String> current = Map.of("uuid-a", "sha-a", "uuid-b", "sha-b");
        assertFalse(NetworkSync.hasManifestChanged(previous, current));
    }

    @Test
    void hasManifestChanged_uuidAdded_returnsTrue() {
        Map<String, String> previous = Map.of("uuid-a", "sha-a");
        Map<String, String> current = Map.of("uuid-a", "sha-a", "uuid-b", "sha-b");
        assertTrue(NetworkSync.hasManifestChanged(previous, current));
    }

    @Test
    void hasManifestChanged_uuidRemoved_returnsTrue() {
        Map<String, String> previous = Map.of("uuid-a", "sha-a", "uuid-b", "sha-b");
        Map<String, String> current = Map.of("uuid-a", "sha-a");
        assertTrue(NetworkSync.hasManifestChanged(previous, current));
    }

    @Test
    void hasManifestChanged_shaChanged_returnsTrue() {
        Map<String, String> previous = Map.of("uuid-a", "sha-old");
        Map<String, String> current = Map.of("uuid-a", "sha-new");
        assertTrue(NetworkSync.hasManifestChanged(previous, current));
    }

    @Test
    void hasManifestChanged_emptyBoth_returnsFalse() {
        assertFalse(NetworkSync.hasManifestChanged(Map.of(), Map.of()));
    }

    @Test
    void filterStale_dropsEntriesOlderThanThreshold() {
        long now = 1_000_000_000_000L;
        long threshold = NetworkSync.STALE_THRESHOLD_MILLIS;
        // fresh: 1 hour ago
        Entry fresh = new Entry("fresh-uuid", "https://x/fresh", "sha-f", 0, now - (60L * 60L * 1000L));
        // stale: 9 hours ago (over the 8h threshold)
        Entry stale = new Entry("stale-uuid", "https://x/stale", "sha-s", 0, now - (9L * 60L * 60L * 1000L));

        List<Entry> result = NetworkSync.filterStale(List.of(fresh, stale), now, threshold);

        assertEquals(1, result.size(), "Only the fresh entry should remain");
        assertEquals("fresh-uuid", result.get(0).uuid());
    }
}
