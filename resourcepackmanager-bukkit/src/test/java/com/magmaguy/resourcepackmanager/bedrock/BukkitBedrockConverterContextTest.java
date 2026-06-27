package com.magmaguy.resourcepackmanager.bedrock;

import com.magmaguy.resourcepackmanager.BukkitMockTestSupport;
import com.magmaguy.resourcepackmanager.network.NetworkMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BukkitBedrockConverterContextTest extends BukkitMockTestSupport {

    private final BukkitBedrockConverterContext context = new BukkitBedrockConverterContext();

    @AfterEach
    void cleanProxyConfigFiles() throws Exception {
        Files.deleteIfExists(Path.of("spigot.yml"));
        Files.deleteIfExists(Path.of("paper-global.yml"));
        Files.deleteIfExists(Path.of("config", "paper-global.yml"));
        Files.deleteIfExists(Path.of("config"));
    }

    @Test
    void standaloneServerWithoutBedrockPluginsIsNotABedrockTarget() {
        assertFalse(NetworkMode.isActive());
        assertFalse(context.isBedrockTargetPresent());
    }

    @Test
    void localGeyserMakesStandaloneServerABedrockTargetWithoutNetworkMode() {
        MockBukkit.createMockPlugin("Geyser-Spigot", "2.9.6");

        assertFalse(NetworkMode.isActive());
        assertTrue(context.isBedrockTargetPresent());
    }

    @Test
    void floodgateWithoutLocalGeyserActivatesNetworkModeAndBedrockConversion() throws Exception {
        MockBukkit.createMockPlugin("floodgate", "2.2.4");
        resetNetworkModeCache();

        assertTrue(NetworkMode.isActive());
        assertTrue(context.isBedrockTargetPresent());
    }

    @Test
    void spigotBungeecordForwardingActivatesNetworkModeAndBedrockConversion() throws Exception {
        Files.writeString(Path.of("spigot.yml"), "settings:\n  bungeecord: true\n");
        resetNetworkModeCache();

        assertTrue(NetworkMode.isActive());
        assertTrue(context.isBedrockTargetPresent());
    }

    @Test
    void paperVelocityForwardingActivatesNetworkModeAndBedrockConversion() throws Exception {
        Path configDir = Path.of("config");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("paper-global.yml"), "proxies:\n  velocity:\n    enabled: true\n");
        resetNetworkModeCache();

        assertTrue(NetworkMode.isActive());
        assertTrue(context.isBedrockTargetPresent());
    }
}
