package com.magmaguy.resourcepackmanager.mixer.bedrock;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.magmaguy.resourcepackmanager.mixer.engine.MixerLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TEMPORARY (2026-11-05): the proxy's Bedrock pack merge keeps every backend's custom sounds (508989c). The commit
 * after the one that adds this file retires it; it stays retrievable from history.
 */
final class SoundDefinitionsMergeTest {

    private static final String DEFINITIONS = "sounds/sound_definitions.json";

    @TempDir
    Path temp;

    private final List<String> warnings = new CopyOnWriteArrayList<>();
    private final MixerLogger logger = new MixerLogger() {
        @Override public void info(String message) { }
        @Override public void warn(String message) { warnings.add(message); }
        @Override public void collision(String message) { }
    };

    @Test
    void everyBackendsSoundsStayDefinedAndTheLaterBackendWinsAClash() throws Exception {
        byte[] one = {1, 2, 3, 4};
        byte[] firstClash = {5, 6, 7};
        byte[] two = {8, 9};
        byte[] secondClash = {10, 11, 12, 13, 14};
        File first = backend("first", "1.20.20", """
                {"a:one": {"category": "neutral", "sounds": [{"name": "sounds/a/one"}]},
                 "shared:clash": {"category": "neutral", "sounds": [{"name": "sounds/first/clash"}]}}
                """, Map.of("sounds/a/one.ogg", one, "sounds/first/clash.ogg", firstClash));
        File second = backend("second", "1.21.40", """
                {"b:two": {"category": "record", "sounds": [{"name": "sounds/b/two", "stream": true}]},
                 "shared:clash": {"category": "record", "sounds": [{"name": "sounds/second/clash"}]}}
                """, Map.of("sounds/b/two.ogg", two, "sounds/second/clash.ogg", secondClash));

        File merged = new BedrockPackMerger(logger).merge(List.of(first, second), temp.resolve("merged.zip").toFile(),
                UUID.randomUUID());

        assertNotNull(merged, "merge succeeded: " + warnings);
        try (ZipFile zip = new ZipFile(merged)) {
            JsonObject root = definitions(zip);
            assertEquals("1.21.40", root.get("format_version").getAsString(), "the highest format_version is kept");
            JsonObject definitions = root.getAsJsonObject("sound_definitions");
            assertEquals(Set.of("a:one", "b:two", "shared:clash"), definitions.keySet());
            assertEquals("sounds/second/clash", definitions.getAsJsonObject("shared:clash").getAsJsonArray("sounds")
                    .get(0).getAsJsonObject().get("name").getAsString(), "the later backend wins the clash");
            assertEquals("record", definitions.getAsJsonObject("shared:clash").get("category").getAsString());
            assertArrayEquals(one, bytes(zip, "sounds/a/one.ogg"));
            assertArrayEquals(two, bytes(zip, "sounds/b/two.ogg"));
            assertArrayEquals(firstClash, bytes(zip, "sounds/first/clash.ogg"));
            assertArrayEquals(secondClash, bytes(zip, "sounds/second/clash.ogg"));
        }
        assertTrue(warnings.stream().anyMatch(w -> w.contains("sound_definitions.json key collision on 'shared:clash'")
                        && w.contains("backend #0") && w.contains("backend #1")),
                "the clash is reported: " + warnings);
    }

    @Test
    void anEarlierHigherFormatVersionIsKept() throws Exception {
        File first = backend("first", "1.21.40", "{\"a:one\": {\"sounds\": [{\"name\": \"sounds/a/one\"}]}}",
                Map.of("sounds/a/one.ogg", new byte[]{1}));
        File second = backend("second", "1.20.20", "{\"b:two\": {\"sounds\": [{\"name\": \"sounds/b/two\"}]}}",
                Map.of("sounds/b/two.ogg", new byte[]{2}));

        File merged = new BedrockPackMerger(logger).merge(List.of(first, second), temp.resolve("merged.zip").toFile(),
                UUID.randomUUID());

        assertNotNull(merged, "merge succeeded: " + warnings);
        try (ZipFile zip = new ZipFile(merged)) {
            JsonObject root = definitions(zip);
            assertEquals("1.21.40", root.get("format_version").getAsString());
            assertEquals(Set.of("a:one", "b:two"), root.getAsJsonObject("sound_definitions").keySet());
        }
        assertTrue(warnings.stream().noneMatch(w -> w.contains("collision")), "no clash, no warning: " + warnings);
    }

    @Test
    void backendsWithoutSoundsGetNoDefinitionsFile() throws Exception {
        Map<String, byte[]> texture = Map.of("textures/items/ruby.png", new byte[]{1, 2, 3});
        File first = backend("first", null, null, texture);
        File second = backend("second", null, null, Map.of("textures/items/sapphire.png", new byte[]{4, 5, 6}));

        File merged = new BedrockPackMerger(logger).merge(List.of(first, second), temp.resolve("merged.zip").toFile(),
                UUID.randomUUID());

        assertNotNull(merged, "merge succeeded: " + warnings);
        try (ZipFile zip = new ZipFile(merged)) {
            assertNull(zip.getEntry(DEFINITIONS));
            assertNotNull(zip.getEntry("textures/items/ruby.png"));
        }
    }

    private static JsonObject definitions(ZipFile zip) throws IOException {
        return JsonParser.parseString(new String(bytes(zip, DEFINITIONS), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static byte[] bytes(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        assertNotNull(entry, name + " in the merged pack");
        return zip.getInputStream(entry).readAllBytes();
    }

    /** One backend's Bedrock pack zip: a manifest, an optional sound_definitions.json and the given files. */
    private File backend(String name, String formatVersion, String definitions, Map<String, byte[]> files)
            throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("manifest.json", ("{\"format_version\":2,\"header\":{\"name\":\"" + name + "\",\"uuid\":\""
                + UUID.randomUUID() + "\",\"version\":[1,0,0],\"min_engine_version\":[1,21,0]},\"modules\":[]}")
                .getBytes(StandardCharsets.UTF_8));
        if (definitions != null)
            entries.put(DEFINITIONS, ("{\"format_version\":\"" + formatVersion + "\",\"sound_definitions\":"
                    + definitions + "}").getBytes(StandardCharsets.UTF_8));
        entries.putAll(files);
        Path zip = temp.resolve(name + ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return zip.toFile();
    }
}
