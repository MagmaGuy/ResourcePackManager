package com.magmaguy.resourcepackmanager.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkKeyResolverTest {

    @Test
    void deriveFromFloodgateKeyIsDeterministicAndUuidShaped(@TempDir Path tempDir) throws Exception {
        Path first = tempDir.resolve("first-key.pem");
        Path second = tempDir.resolve("second-key.pem");
        Files.writeString(first, "shared floodgate key");
        Files.writeString(second, "different floodgate key");

        String firstKey = NetworkKeyResolver.deriveFromFloodgateKey(first);

        assertEquals(firstKey, NetworkKeyResolver.deriveFromFloodgateKey(first));
        assertNotEquals(firstKey, NetworkKeyResolver.deriveFromFloodgateKey(second));
        assertDoesNotThrow(() -> UUID.fromString(firstKey));
    }

    @Test
    void shortHashForRelayProducesStableOpaquePathToken() {
        String key = "11111111-2222-3333-4444-555555555555";
        String first = NetworkKeyResolver.shortHashForRelay(key);

        assertEquals(first, NetworkKeyResolver.shortHashForRelay(key));
        assertEquals(32, first.length());
        assertTrue(first.matches("[0-9a-f]{32}"));
        assertNull(NetworkKeyResolver.shortHashForRelay("short"));
        assertNull(NetworkKeyResolver.shortHashForRelay(null));
    }
}
