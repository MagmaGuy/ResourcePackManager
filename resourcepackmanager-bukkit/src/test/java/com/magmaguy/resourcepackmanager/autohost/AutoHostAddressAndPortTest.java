package com.magmaguy.resourcepackmanager.autohost;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization coverage for the pure address/port helpers the hosting flow
 * leans on. {@link AutoHost#isNonRoutableHost(String)} is layer 1 of the
 * self-host-first reachability check; {@code resolveHttpPort} decides which
 * port the backend HTTP server binds. Only literal IPs and a plain hostname
 * appear here so no test ever performs DNS resolution.
 */
class AutoHostAddressAndPortTest {

    @Test
    void loopbackUnspecifiedAndBlankHostsAreNonRoutable() {
        assertTrue(AutoHost.isNonRoutableHost(null));
        assertTrue(AutoHost.isNonRoutableHost("  "));
        assertTrue(AutoHost.isNonRoutableHost("localhost"));
        assertTrue(AutoHost.isNonRoutableHost("LOCALHOST"));
        assertTrue(AutoHost.isNonRoutableHost("0.0.0.0"));
        assertTrue(AutoHost.isNonRoutableHost("127.0.0.1"));
        assertTrue(AutoHost.isNonRoutableHost("127.63.9.9"));
        assertTrue(AutoHost.isNonRoutableHost("::1"));
        assertTrue(AutoHost.isNonRoutableHost("[::1]"));
    }

    @Test
    void rfc1918AndLinkLocalRangesAreNonRoutable() {
        assertTrue(AutoHost.isNonRoutableHost("10.0.0.5"));
        assertTrue(AutoHost.isNonRoutableHost("192.168.1.10"));
        assertTrue(AutoHost.isNonRoutableHost("169.254.10.10"));
        assertTrue(AutoHost.isNonRoutableHost("172.16.0.1"));
        assertTrue(AutoHost.isNonRoutableHost("172.31.255.255"));
        assertTrue(AutoHost.isNonRoutableHost("fe80::1%eth0"));
        assertTrue(AutoHost.isNonRoutableHost("fc00::1234"));
        assertTrue(AutoHost.isNonRoutableHost("fd12:3456::1"));
    }

    @Test
    void the172SlashTwelveBoundaryIsExact() {
        assertFalse(AutoHost.isNonRoutableHost("172.15.0.1"));
        assertFalse(AutoHost.isNonRoutableHost("172.32.0.1"));
    }

    @Test
    void publicAddressesAndHostnamesAreRoutable() {
        assertFalse(AutoHost.isNonRoutableHost("8.8.8.8"));
        assertFalse(AutoHost.isNonRoutableHost("203.0.113.7"));
        assertFalse(AutoHost.isNonRoutableHost("play.example.com"));
        assertFalse(AutoHost.isNonRoutableHost("2001:4860:4860::8888"));
    }

    @Test
    void explicitPortWinsOverDerivation() throws IOException {
        assertEquals(9876, AutoHost.resolveHttpPort(9876, 30067, 1));
    }

    @Test
    void sentinelDerivesMinecraftPortPlusOffset() throws IOException {
        assertEquals(30068, AutoHost.resolveHttpPort(-1, 30067, 1));
        assertEquals(25665, AutoHost.resolveHttpPort(-1, 25565, 100));
    }

    @Test
    void outOfRangePortsFailWithTheirSource() {
        IOException explicit = assertThrows(IOException.class,
                () -> AutoHost.resolveHttpPort(70000, 25565, 1));
        assertTrue(explicit.getMessage().contains("configured selfHostPort"));

        IOException derived = assertThrows(IOException.class,
                () -> AutoHost.resolveHttpPort(-1, 65535, 1));
        assertTrue(derived.getMessage().contains("between 1 and 65535"));
    }

    @Test
    void occupiedAutoDerivedPortFallsBackToFreePort() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0)) {
            int occupiedPort = occupied.getLocalPort();
            try (var server = AutoHost.startPackHttpServerWithFallback(occupiedPort, true)) {
                assertTrue(server.port() > 0);
                assertTrue(server.port() != occupiedPort,
                        "Fallback must not reuse the occupied auto-derived port");
            }
        }
    }

    @Test
    void occupiedExplicitPortStillFails() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0)) {
            int occupiedPort = occupied.getLocalPort();
            assertThrows(IOException.class,
                    () -> AutoHost.startPackHttpServerWithFallback(occupiedPort, false));
        }
    }
}
