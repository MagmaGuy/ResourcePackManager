package com.magmaguy.resourcepackmanager.proxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.magmaguy.resourcepackmanager.bridge.UniversalPluginJarInspector;
import com.magmaguy.resourcepackmanager.http.PackHttpServer;
import com.magmaguy.resourcepackmanager.mixer.engine.MixerLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Production HTTP/poll/merge/deploy/update behavior; no proxy or Geyser process is simulated. */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class NetworkSyncFeatureTest {
    private static final int OFFSET = 1;
    private static final String NETWORK_KEY = "rspm-local-sync-test-key";

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void overlappingPollsDoNotRepeatMergeOrAccumulateScratch(@TempDir Path tempDir) throws Exception {
        Fixture fixture = writeFixture(tempDir.resolve("backend"), "single_backend");
        List<String> diagnostics = new CopyOnWriteArrayList<>();
        AtomicInteger merges = new AtomicInteger();
        AtomicInteger publications = new AtomicInteger();
        CountDownLatch mergeEntered = new CountDownLatch(1);
        CountDownLatch finishMerge = new CountDownLatch(1);
        ProxyLogger blockingLogger = new ProxyLogger() {
            public void info(String message) {
                diagnostics.add(message);
                if (!message.startsWith("NetworkSync: inbox stabilized")) return;
                if (merges.incrementAndGet() != 1) return;
                mergeEntered.countDown();
                try {
                    if (!finishMerge.await(10, TimeUnit.SECONDS))
                        throw new AssertionError("merge was never released");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            }
            public void warn(String message) { diagnostics.add(message); }
            public void warn(String message, Throwable failure) { diagnostics.add(message + ": " + failure); }
        };
        String previousRelay = System.setProperty("rspm.test.disableRemoteRelay", "true");
        var workers = Executors.newFixedThreadPool(4);
        NetworkSync sync = null;
        try (PackHttpServer backend = backend(fixture, null)) {
            Path work = tempDir.resolve("work");
            Path geyser = tempDir.resolve("Geyser-Velocity");
            Files.createDirectories(geyser);
            sync = new NetworkSync(blockingLogger, noScheduler(),
                    () -> List.of(descriptor("single", backend)), work.toFile(), OFFSET,
                    mixerLogger(diagnostics), geyser.toFile(), NETWORK_KEY,
                    pack -> publications.incrementAndGet(), ignored -> false);
            sync.pollOnce(); // Establish stable input hashes.
            NetworkSync active = sync;
            Future<?> firstMerge = workers.submit(active::pollOnce);
            assertTrue(mergeEntered.await(5, TimeUnit.SECONDS), () -> String.join("\n", diagnostics));
            List<Future<?>> overlapping = new ArrayList<>();
            for (int i = 0; i < 12; i++) overlapping.add(workers.submit(active::pollOnce));
            for (Future<?> poll : overlapping) poll.get(3, TimeUnit.SECONDS);
            assertEquals(1, merges.get(), "overlapping scheduler dispatch must not start another merge");
            assertEquals(0, publications.get());
            assertTrue(diagnostics.stream().anyMatch(line -> line.contains("skipping overlapping poll")));
            finishMerge.countDown();
            firstMerge.get(10, TimeUnit.SECONDS);
            assertEquals(1, publications.get(), () -> String.join("\n", diagnostics));
            assertPackContains(active.current().packFile().toPath(), fixture);
            assertMappingsContain(work.resolve("merged/rspm_geyser_mappings.json"), fixture);
            for (int i = 0; i < 20; i++) active.pollOnce();
            assertEquals(1, merges.get(), "unchanged inputs must not repeatedly merge");
            assertEquals(1, publications.get());
            try (var paths = Files.walk(work)) {
                assertEquals(List.of(), paths.filter(path -> path.getFileName().toString()
                        .startsWith("_bedrock_merge_scratch_") || path.getFileName().toString()
                        .startsWith(".rspm-network-merge-")).toList(), "completed merges must clean staging");
            }
        } finally {
            finishMerge.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            if (sync != null) sync.stop();
            if (previousRelay == null) System.clearProperty("rspm.test.disableRemoteRelay");
            else System.setProperty("rspm.test.disableRemoteRelay", previousRelay);
        }
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void mergesTwoBackendsAndStagesProtectedExecutableUpdate(
            @TempDir Path tempDir) throws Exception {
        Fixture a = writeFixture(tempDir.resolve("backend-a"), "backend_a");
        Fixture b = writeFixture(tempDir.resolve("backend-b"), "backend_b");
        Path offeredUpdate = makeUniversal(
                tempDir.resolve("backend-a/ResourcePackManager.jar"),
                "2.3.0", "backend-new");
        Path running = makeUniversal(
                tempDir.resolve("proxy-root/plugins/ResourcePackManager.jar"),
                "2.2.9", "proxy-old");

        String previousRelay = System.setProperty("rspm.test.disableRemoteRelay", "true");
        try (PackHttpServer backendA = backend(a, offeredUpdate);
             PackHttpServer backendB = backend(b, null)) {
            assertNotEquals(backendA.port(), backendB.port());
            assertUpdateRouteRejectsMissingToken(backendA);

            BackendListProvider backends = () -> List.of(
                     descriptor("backend-a", backendA), descriptor("backend-b", backendB));
            Path work = tempDir.resolve("proxy-work");
            Path geyser = tempDir.resolve("proxy-plugins/Geyser-Velocity");
            Files.createDirectories(geyser);
            AtomicReference<MergedPack> published = new AtomicReference<>();
            List<String> diagnostics = new ArrayList<>();
            ProxyPluginUpdateCoordinator coordinator = new ProxyPluginUpdateCoordinator(
                    tempDir.resolve("proxy-root/update-work"),
                    running,
                    geyser,
                    logger(diagnostics));
            NetworkSync sync = new NetworkSync(
                    logger(diagnostics), noScheduler(), backends, work.toFile(), OFFSET,
                    mixerLogger(diagnostics), geyser.toFile(), NETWORK_KEY, published::set,
                    coordinator::accept);

            try {
                sync.pollOnce();
                diagnostics.add("First poll: " + sync.snapshot().fetchOutcomes());
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
                assertNotNull(merged, () -> String.join("\n", diagnostics) + "\nSecond poll: " + sync.snapshot().fetchOutcomes());
                assertTrue(merged.packFile().isFile());
                assertPackContains(merged.packFile().toPath(), a, b);

                Path mappings = work.resolve("merged/rspm_geyser_mappings.json");
                Path deployed = geyser.resolve("custom_mappings/rspm_geyser_mappings.json");
                assertMappingsContain(mappings, a, b);
                assertTrue(Files.isRegularFile(deployed));
                assertEquals(-1L, Files.mismatch(mappings, deployed));
            } finally {
                sync.stop();
            }
        } finally {
            if (previousRelay == null) System.clearProperty("rspm.test.disableRemoteRelay");
            else System.setProperty("rspm.test.disableRemoteRelay", previousRelay);
        }
    }

    private static PackHttpServer backend(Fixture fixture, Path executableUpdate) throws IOException {
        PackHttpServer server = PackHttpServer.start(fixture.pack().toFile(), 0, PackHttpServer.BEDROCK_PACK_PATH);
        try {
            server.registerFileRoute(PackHttpServer.GEYSER_MAPPINGS_PATH, fixture.mappings().toFile(), "application/json");
            if (executableUpdate != null)
                server.registerProtectedExecutableRoute(PackHttpServer.EXECUTABLE_UPDATE_PATH,
                        executableUpdate::toFile, () -> NETWORK_KEY);
            return server;
        } catch (RuntimeException | Error failure) {
            server.close();
            throw failure;
        }
    }

    private static BackendListProvider.Backend descriptor(String name, PackHttpServer server) {
        return new BackendListProvider.Backend(name, "127.0.0.1", server.port() - OFFSET);
    }

    private static void assertUpdateRouteRejectsMissingToken(PackHttpServer backend) throws Exception {
        URI updateUri = URI.create("http://127.0.0.1:" + backend.port() + PackHttpServer.EXECUTABLE_UPDATE_PATH);
        HttpURLConnection connection = (HttpURLConnection) updateUri.toURL().openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(2000);
        try {
            assertEquals(401, connection.getResponseCode());
        } finally {
            connection.disconnect();
        }
    }

    private static Fixture writeFixture(Path directory, String marker)
            throws Exception {
        Files.createDirectories(directory);
        Path pack = directory.resolve("bedrock.zip");
        String texture = "textures/items/" + marker + ".png";
        UUID header = UUID.nameUUIDFromBytes((marker + ":header").getBytes(StandardCharsets.UTF_8));
        UUID module = UUID.nameUUIDFromBytes((marker + ":module").getBytes(StandardCharsets.UTF_8));
        String identifier = "rspm:" + marker;
        Path mappings = directory.resolve("mappings.json");
        Files.writeString(mappings, """
                {"format_version":2,"items":{"minecraft:leather_horse_armor":[{
                "type":"definition","bedrock_identifier":"%s",
                "bedrock_options":{"icon":"%s_item"},"model":"%s"}]}}
                """.formatted(identifier, marker, identifier), StandardCharsets.UTF_8);
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
            byte[] mappingBytes = Files.readAllBytes(mappings);
            entry(zip, "rspm_artifact_set.json", """
                    {"formatVersion":1,"generationId":"%s","mappingsPresent":true,
                    "mappingsSha1":"%s","mappingsSize":%d}
                    """.formatted(header, java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-1").digest(mappingBytes)), mappingBytes.length));
        }
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

    private static ProxyLogger logger(List<String> diagnostics) {
        return new ProxyLogger() {
            public void info(String message) { diagnostics.add(message); }
            public void warn(String message) { diagnostics.add(message); }
            public void warn(String message, Throwable throwable) { diagnostics.add(message + ": " + throwable); }
        };
    }

    private static MixerLogger mixerLogger(List<String> diagnostics) {
        return new MixerLogger() {
            public void info(String message) { diagnostics.add(message); }
            public void warn(String message) { diagnostics.add(message); }
            public void collision(String message) { diagnostics.add(message); }
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
