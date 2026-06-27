package com.magmaguy.resourcepackmanager.network;

import com.magmaguy.resourcepackmanager.BukkitMockTestSupport;
import com.magmaguy.resourcepackmanager.http.NetworkKeyResolver;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NetworkModeTest extends BukkitMockTestSupport {

    @Test
    void networkKeyDerivesFromFloodgatePemWhenPresent() throws Exception {
        Path keyPem = plugin.getDataFolder()
                .getParentFile()
                .toPath()
                .resolve("floodgate")
                .resolve("key.pem");
        Files.createDirectories(keyPem.getParent());
        Files.writeString(keyPem, "shared floodgate private key fixture");

        assertEquals(NetworkKeyResolver.deriveFromFloodgateKey(keyPem), NetworkMode.getNetworkKey());
    }
}
