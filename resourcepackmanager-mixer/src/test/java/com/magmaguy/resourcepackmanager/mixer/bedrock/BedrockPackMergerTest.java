package com.magmaguy.resourcepackmanager.mixer.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.magmaguy.resourcepackmanager.mixer.engine.MixerLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockPackMergerTest {

    private static final UUID NETWORK_UUID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void identicalInputsProduceIdenticalMergedPackAndVersion(@TempDir Path tempDir) throws Exception {
        File input = createBedrockPack(tempDir.resolve("input.zip"), "same-content");
        File first = tempDir.resolve("first.zip").toFile();
        File second = tempDir.resolve("second.zip").toFile();

        BedrockPackMerger merger = new BedrockPackMerger(noopLogger());

        assertNotNull(merger.merge(List.of(input), first, NETWORK_UUID));
        Thread.sleep(5L);
        assertNotNull(merger.merge(List.of(input), second, NETWORK_UUID));

        assertArrayEquals(Files.readAllBytes(first.toPath()), Files.readAllBytes(second.toPath()));
        assertArrayEquals(readManifestVersion(first), readManifestVersion(second));
    }

    @Test
    void changedInputChangesMergedManifestVersion(@TempDir Path tempDir) throws Exception {
        File firstInput = createBedrockPack(tempDir.resolve("first-input.zip"), "first-content");
        File secondInput = createBedrockPack(tempDir.resolve("second-input.zip"), "second-content");
        File first = tempDir.resolve("first.zip").toFile();
        File second = tempDir.resolve("second.zip").toFile();

        BedrockPackMerger merger = new BedrockPackMerger(noopLogger());

        assertNotNull(merger.merge(List.of(firstInput), first, NETWORK_UUID));
        assertNotNull(merger.merge(List.of(secondInput), second, NETWORK_UUID));

        assertFalse(Arrays.equals(readManifestVersion(first), readManifestVersion(second)));
    }

    @Test
    void mergeCompactsLongBackendPathsAndRewritesJsonReferences(@TempDir Path tempDir) throws Exception {
        String longStem = "fmm_craftenmine_basic_item_pack_velocity_enhancer_mk2_crossbow_draw_start";
        File input = createBedrockPackWithLongPaths(tempDir.resolve("input.zip"), longStem);
        File merged = tempDir.resolve("merged.zip").toFile();

        BedrockPackMerger merger = new BedrockPackMerger(noopLogger());

        assertNotNull(merger.merge(List.of(input), merged, NETWORK_UUID));

        List<String> entries = zipEntries(merged);
        assertTrue(entries.stream().noneMatch(entry -> entry.length() >= 80), entries.toString());
        assertTrue(entries.stream().noneMatch(entry -> entry.contains(longStem)), entries.toString());

        try (ZipFile zipFile = new ZipFile(merged)) {
            ZipEntry itemTexture = zipFile.getEntry("textures/item_texture.json");
            assertNotNull(itemTexture);
            try (var stream = zipFile.getInputStream(itemTexture)) {
                String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                assertFalse(json.contains(longStem));
                assertTrue(json.contains("textures/items/"));
            }
        }
    }

    private static File createBedrockPack(Path zipPath, String textureContent) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipPath.toFile()))) {
            writeEntry(zos, "manifest.json", """
                    {
                      "format_version": 2,
                      "header": {
                        "name": "Backend Pack",
                        "description": "test",
                        "uuid": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                        "version": [1, 0, 0],
                        "min_engine_version": [1, 21, 0]
                      },
                      "modules": [
                        {
                          "type": "resources",
                          "uuid": "ffffffff-1111-2222-3333-444444444444",
                          "version": [1, 0, 0]
                        }
                      ]
                    }
                    """);
            writeEntry(zos, "textures/item_texture.json", """
                    {
                      "resource_pack_name": "Backend Pack",
                      "texture_name": "atlas.items",
                      "texture_data": {
                        "test_item": {
                          "textures": "textures/items/test_item"
                        }
                      }
                    }
                    """);
            writeEntry(zos, "textures/items/test_item.png", textureContent);
        }
        return zipPath.toFile();
    }

    private static File createBedrockPackWithLongPaths(Path zipPath, String longStem) throws IOException {
        String longTexture = "textures/items/" + longStem + ".png";
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipPath.toFile()))) {
            writeEntry(zos, "manifest.json", """
                    {
                      "format_version": 2,
                      "header": {
                        "name": "Backend Pack",
                        "description": "test",
                        "uuid": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                        "version": [1, 0, 0],
                        "min_engine_version": [1, 21, 0]
                      },
                      "modules": [
                        {
                          "type": "resources",
                          "uuid": "ffffffff-1111-2222-3333-444444444444",
                          "version": [1, 0, 0]
                        }
                      ]
                    }
                    """);
            writeEntry(zos, "textures/item_texture.json", """
                    {
                      "resource_pack_name": "Backend Pack",
                      "texture_name": "atlas.items",
                      "texture_data": {
                        "test_item": {
                          "textures": "%s"
                        }
                      }
                    }
                    """.formatted(longTexture.substring(0, longTexture.length() - ".png".length())));
            writeEntry(zos, longTexture, "png");
            writeEntry(zos, "models/entity/freeminecraftmodels/" + longStem + ".geo.json", "{}");
            writeEntry(zos, "render_controllers/" + longStem + ".render_controllers.json", "{}");
            writeEntry(zos, "animation_controllers/" + longStem + ".animation_controllers.json", "{}");
        }
        return zipPath.toFile();
    }

    private static void writeEntry(ZipOutputStream zos, String name, String content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        zos.putNextEntry(entry);
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static int[] readManifestVersion(File zip) throws IOException {
        try (ZipFile zipFile = new ZipFile(zip)) {
            ZipEntry manifestEntry = zipFile.getEntry("manifest.json");
            assertNotNull(manifestEntry);
            try (InputStreamReader reader = new InputStreamReader(
                    zipFile.getInputStream(manifestEntry), StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                JsonArray version = root.getAsJsonObject("header").getAsJsonArray("version");
                return new int[]{
                        version.get(0).getAsInt(),
                        version.get(1).getAsInt(),
                        version.get(2).getAsInt()
                };
            }
        }
    }

    private static List<String> zipEntries(File zip) throws IOException {
        List<String> entries = new ArrayList<>();
        try (ZipFile zipFile = new ZipFile(zip)) {
            zipFile.stream()
                    .filter(entry -> !entry.isDirectory())
                    .forEach(entry -> entries.add(entry.getName()));
        }
        return entries;
    }

    private static MixerLogger noopLogger() {
        return new MixerLogger() {
            @Override
            public void info(String message) {
            }

            @Override
            public void warn(String message) {
            }

            @Override
            public void collision(String message) {
            }
        };
    }
}
