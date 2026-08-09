package com.magmaguy.resourcepackmanager.proxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.magmaguy.resourcepackmanager.bridge.UniversalPluginJarInspector;
import com.magmaguy.resourcepackmanager.http.NetworkAccessToken;
import com.magmaguy.resourcepackmanager.http.PackHttpServer;
import com.magmaguy.resourcepackmanager.mixer.engine.MixerLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Docker-only coverage for the real proxy HTTP/poll/merge/deploy/update path. */
class NetworkSyncDockerSystemTest {
    private static final DockerImageName IMAGE = DockerImageName.parse("nginx:1.27.4-alpine");
    private static final int HTTP_PORT = 8080;
    private static final int OFFSET = 1;
    private static final String NETWORK_KEY = "rspm-docker-system-network-key";

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void mergesTwoIsolatedBackendsAndStagesProtectedExecutableUpdate(
            @TempDir Path tempDir) throws Exception {
        Path nginxConfig = writeNginxConfig(tempDir);
        Fixture a = writeFixture(tempDir.resolve("backend-a"), "backend_a");
        Fixture b = writeFixture(tempDir.resolve("backend-b"), "backend_b");
        Path offeredUpdate = makeUniversal(
                tempDir.resolve("backend-a/ResourcePackManager.jar"),
                "2.3.0", "backend-new");
        Path running = makeUniversal(
                tempDir.resolve("proxy-root/plugins/ResourcePackManager.jar"),
                "2.2.9", "proxy-old");

        try (GenericContainer<?> backendA = backend(nginxConfig, a, offeredUpdate);
             GenericContainer<?> backendB = backend(nginxConfig, b, null)) {
            Startables.deepStart(Stream.of(backendA, backendB)).join();
            assertEquals(List.of(HTTP_PORT), backendA.getExposedPorts());
            assertEquals(List.of(HTTP_PORT), backendB.getExposedPorts());
            assertNotEquals(backendA.getMappedPort(HTTP_PORT), backendB.getMappedPort(HTTP_PORT));
            assertUpdateRouteRejectsMissingToken(backendA);

            BackendListProvider backends = () -> List.of(
                     descriptor("backend-a", backendA), descriptor("backend-b", backendB));
            Path work = tempDir.resolve("proxy-work");
            Path geyser = tempDir.resolve("proxy-plugins/Geyser-Velocity");
            Files.createDirectories(geyser);
            AtomicReference<MergedPack> published = new AtomicReference<>();
            ProxyPluginUpdateCoordinator coordinator = new ProxyPluginUpdateCoordinator(
                    tempDir.resolve("proxy-root/update-work"),
                    running,
                    geyser,
                    logger());
            NetworkSync sync = new NetworkSync(
                    logger(), noScheduler(), backends, work.toFile(), OFFSET,
                    mixerLogger(), geyser.toFile(), NETWORK_KEY, published::set,
                    coordinator::accept);

            sync.pollOnce();
            assertNull(published.get(), "first poll only establishes the stability baseline");
            assertTrue(Files.isRegularFile(coordinator.pendingJar()));
            assertTrue(Files.isRegularFile(coordinator.pendingManifest()));
            assertArrayEquals(Files.readAllBytes(offeredUpdate),
                    Files.readAllBytes(coordinator.pendingJar()));
            assertArrayEquals("proxy-old".getBytes(StandardCharsets.UTF_8), marker(running));
            assertFalse(Files.exists(
                    geyser.resolve("extensions/update/ResourcePackManager.jar")));
            sync.pollOnce();

            MergedPack merged = published.get();
            assertNotNull(merged);
            assertTrue(merged.packFile().isFile());
            assertPackContains(merged.packFile().toPath(), a, b);

            Path mappings = work.resolve("merged/rspm_geyser_mappings.json");
            Path deployed = geyser.resolve("custom_mappings/rspm_geyser_mappings.json");
            assertMappingsContain(mappings, a, b);
            assertTrue(Files.isRegularFile(deployed));
            assertEquals(-1L, Files.mismatch(mappings, deployed));
        }
    }

    private static GenericContainer<?> backend(
            Path nginxConfig, Fixture fixture, Path executableUpdate) {
        GenericContainer<?> container = new GenericContainer<>(IMAGE)
                .withExposedPorts(HTTP_PORT)
                .withCopyFileToContainer(MountableFile.forHostPath(nginxConfig.toAbsolutePath().toString()),
                        "/etc/nginx/conf.d/default.conf")
                .withCopyFileToContainer(MountableFile.forHostPath(fixture.pack().toAbsolutePath().toString()),
                        "/usr/share/nginx/html/bedrock.zip")
                .withCopyFileToContainer(MountableFile.forHostPath(fixture.mappings().toAbsolutePath().toString()),
                        "/usr/share/nginx/html/mappings.json")
                .waitingFor(Wait.forHttp("/bedrock.zip").forPort(HTTP_PORT).forStatusCode(200)
                        .withStartupTimeout(Duration.ofSeconds(45)));
        if (executableUpdate != null) {
            container.withCopyFileToContainer(
                    MountableFile.forHostPath(executableUpdate.toAbsolutePath().toString()),
                    "/usr/share/nginx/html/rspm-update.jar");
        }
        return container;
    }

    private static BackendListProvider.Backend descriptor(String name, GenericContainer<?> container) {
        int mappedHttpPort = container.getMappedPort(HTTP_PORT);
        return new BackendListProvider.Backend(name, container.getHost(), mappedHttpPort - OFFSET);
    }

    private static Path writeNginxConfig(Path tempDir) throws IOException {
        Path config = tempDir.resolve("rspm-nginx.conf");
        Files.writeString(config, """
                server {
                    listen 8080;
                    server_name _;
                    root /usr/share/nginx/html;
                    location = /rspm-update.jar {
                        if ($http_authorization != "%s") { return 401; }
                        add_header Cache-Control "no-store" always;
                        add_header X-Content-Type-Options "nosniff" always;
                        try_files $uri =404;
                    }
                    location / { try_files $uri =404; }
                }
                """.formatted(NetworkAccessToken.authorizationValue(
                        PackHttpServer.EXECUTABLE_UPDATE_TOKEN_DOMAIN, NETWORK_KEY)),
                StandardCharsets.UTF_8);
        return config;
    }

    private static void assertUpdateRouteRejectsMissingToken(
            GenericContainer<?> backend) throws Exception {
        String host = backend.getHost();
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        URI updateUri = URI.create("http://" + host + ":"
                + backend.getMappedPort(HTTP_PORT)
                + PackHttpServer.EXECUTABLE_UPDATE_PATH);
        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(updateUri).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(401, response.statusCode());
    }

    private static Fixture writeFixture(Path directory, String marker)
            throws IOException {
        Files.createDirectories(directory);
        Path pack = directory.resolve("bedrock.zip");
        String texture = "textures/items/" + marker + ".png";
        UUID header = UUID.nameUUIDFromBytes((marker + ":header").getBytes(StandardCharsets.UTF_8));
        UUID module = UUID.nameUUIDFromBytes((marker + ":module").getBytes(StandardCharsets.UTF_8));
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(pack.toFile()))) {
            entry(zip, "manifest.json", """
                    {"format_version":2,"header":{"name":"%s","description":"system test",
                    "uuid":"%s","version":[1,0,0],"min_engine_version":[1,21,0]},
                    "modules":[{"type":"resources","uuid":"%s","version":[1,0,0]}]}
                    """.formatted(marker, header, module));
            entry(zip, "textures/item_texture.json", """
                    {"resource_pack_name":"%s","texture_name":"atlas.items","texture_data":{
                    "%s_item":{"textures":"textures/items/%s"}}}
                    """.formatted(marker, marker, marker));
            entry(zip, texture, marker);
        }
        String identifier = "rspm:" + marker;
        Path mappings = directory.resolve("mappings.json");
        Files.writeString(mappings, """
                {"format_version":2,"items":{"minecraft:leather_horse_armor":[{
                "type":"definition","bedrock_identifier":"%s",
                "bedrock_options":{"icon":"%s_item"},"model":"%s"}]}}
                """.formatted(identifier, marker, identifier), StandardCharsets.UTF_8);
        return new Fixture(pack, mappings, texture, marker, identifier);
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static Path makeUniversal(Path path, String version, String marker)
            throws IOException {
        Files.createDirectories(path.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            entry(zip, "plugin.yml",
                    "name: ResourcePackManager\nversion: " + version + "\n");
            entry(zip, "velocity-plugin.json",
                    "{\"id\":\"resourcepackmanager\",\"version\":\"" + version + "\"}");
            entry(zip, "bungee.yml",
                    "name: ResourcePackManager\nversion: " + version + "\n");
            entry(zip, "extension.yml",
                    "name: ResourcePackManagerGeyserBridge\nversion: " + version + "\n");
            for (String entrypoint : UniversalPluginJarInspector.ENTRYPOINT_ENTRIES) {
                entry(zip, entrypoint, "class");
            }
            entry(zip, "fixture-marker.txt", marker);
        }
        return path;
    }

    private static byte[] marker(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return zip.getInputStream(zip.getEntry("fixture-marker.txt")).readAllBytes();
        }
    }

    private static void assertPackContains(Path merged, Fixture... fixtures) throws IOException {
        try (ZipFile zip = new ZipFile(merged.toFile())) {
            for (Fixture fixture : fixtures) {
                ZipEntry entry = zip.getEntry(fixture.texture());
                assertNotNull(entry, "missing " + fixture.texture());
                try (var input = zip.getInputStream(entry)) {
                    assertArrayEquals(fixture.marker().getBytes(StandardCharsets.UTF_8), input.readAllBytes());
                }
            }
        }
    }

    private static void assertMappingsContain(Path mappings, Fixture... fixtures) throws IOException {
        assertTrue(Files.isRegularFile(mappings));
        JsonObject root = JsonParser.parseString(Files.readString(mappings)).getAsJsonObject();
        JsonArray definitions = root.getAsJsonObject("items")
                .getAsJsonArray("minecraft:leather_horse_armor");
        for (Fixture fixture : fixtures) {
            boolean found = false;
            for (var definition : definitions) {
                if (definition.isJsonObject()
                        && fixture.identifier().equals(definition.getAsJsonObject()
                        .get("bedrock_identifier").getAsString())) {
                    found = true;
                    break;
                }
            }
            assertTrue(found, "missing " + fixture.identifier());
        }
    }

    private static ProxyLogger logger() {
        return new ProxyLogger() {
            public void info(String message) { }
            public void warn(String message) { }
            public void warn(String message, Throwable throwable) { }
        };
    }

    private static MixerLogger mixerLogger() {
        return new MixerLogger() {
            public void info(String message) { }
            public void warn(String message) { }
            public void collision(String message) { }
        };
    }

    private static ProxySchedulerAdapter noScheduler() {
        return new ProxySchedulerAdapter() {
            public Cancellable scheduleRepeating(Runnable task, long initialDelayMillis, long intervalMillis) {
                throw new AssertionError("unexpected scheduling");
            }
        };
    }

    private record Fixture(Path pack, Path mappings, String texture, String marker, String identifier) { }
}
