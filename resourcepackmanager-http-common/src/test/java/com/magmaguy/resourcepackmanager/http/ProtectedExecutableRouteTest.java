package com.magmaguy.resourcepackmanager.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Route-level coverage for {@link PackHttpServer#registerProtectedExecutableRoute}:
 * the real HTTP listener, real requests, and the real bearer-token check. This
 * is the in-process half of the update-transport story; the Docker lab covers
 * the same contract against remote containers.
 */
class ProtectedExecutableRouteTest {

    private static final String NETWORK_KEY = "0f7c9ac0-1111-2222-3333-444455556666";

    private record Fixture(PackHttpServer server, byte[] jarBytes, String url) {
    }

    private static Fixture startFixture(Path tempDir, AtomicInteger keyResolutions,
                                        String networkKey) throws Exception {
        Path jar = tempDir.resolve("update.jar");
        byte[] bytes = "fake-universal-jar-bytes".getBytes();
        Files.write(jar, bytes);
        Path pack = tempDir.resolve("pack.zip");
        Files.write(pack, new byte[]{1, 2, 3});

        PackHttpServer server = PackHttpServer.start(pack.toFile(), 0, "/rspm.zip");
        server.registerProtectedExecutableRoute(
                PackHttpServer.EXECUTABLE_UPDATE_PATH,
                jar::toFile,
                () -> {
                    keyResolutions.incrementAndGet();
                    return networkKey;
                });
        String url = server.urlOn("127.0.0.1").replace("/rspm.zip",
                PackHttpServer.EXECUTABLE_UPDATE_PATH);
        return new Fixture(server, bytes, url);
    }

    private static HttpResponse<byte[]> get(String url, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).GET();
        if (authorization != null) request.header("Authorization", authorization);
        return HttpClient.newHttpClient()
                .send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void credentiallessRequestGets401WithoutResolvingTheNetworkKey(@TempDir Path tempDir)
            throws Exception {
        AtomicInteger keyResolutions = new AtomicInteger();
        Fixture fixture = startFixture(tempDir, keyResolutions, NETWORK_KEY);
        try (PackHttpServer ignored = fixture.server()) {
            HttpResponse<byte[]> response = get(fixture.url(), null);
            assertEquals(401, response.statusCode());
            assertEquals("Bearer realm=\"rspm-network-update\"",
                    response.headers().firstValue("WWW-Authenticate").orElse(null));
            assertEquals(0, keyResolutions.get(),
                    "unauthenticated probes must not trigger network-key resolution");
        }
    }

    @Test
    void wrongBearerAndRawNetworkKeyAreBothRejected(@TempDir Path tempDir) throws Exception {
        Fixture fixture = startFixture(tempDir, new AtomicInteger(), NETWORK_KEY);
        try (PackHttpServer ignored = fixture.server()) {
            assertEquals(401, get(fixture.url(), "Bearer not-the-token").statusCode());
            assertEquals(401, get(fixture.url(), "Bearer " + NETWORK_KEY).statusCode(),
                    "the raw network key must never work as a bearer token");
        }
    }

    @Test
    void derivedBearerTokenDownloadsTheExactJarBytes(@TempDir Path tempDir) throws Exception {
        Fixture fixture = startFixture(tempDir, new AtomicInteger(), NETWORK_KEY);
        try (PackHttpServer ignored = fixture.server()) {
            HttpResponse<byte[]> response = get(fixture.url(),
                    NetworkAccessToken.authorizationValue(
                            PackHttpServer.EXECUTABLE_UPDATE_TOKEN_DOMAIN, NETWORK_KEY));
            assertEquals(200, response.statusCode());
            assertArrayEquals(fixture.jarBytes(), response.body());
            assertEquals("application/java-archive",
                    response.headers().firstValue("Content-Type").orElse(null));
            assertEquals("no-store",
                    response.headers().firstValue("Cache-Control").orElse(null));
            assertEquals("nosniff",
                    response.headers().firstValue("X-Content-Type-Options").orElse(null));
        }
    }

    @Test
    void missingNetworkKeyFailsClosedWith503(@TempDir Path tempDir) throws Exception {
        Fixture fixture = startFixture(tempDir, new AtomicInteger(), null);
        try (PackHttpServer ignored = fixture.server()) {
            HttpResponse<byte[]> response = get(fixture.url(), "Bearer anything");
            assertEquals(503, response.statusCode());
        }
    }
}
