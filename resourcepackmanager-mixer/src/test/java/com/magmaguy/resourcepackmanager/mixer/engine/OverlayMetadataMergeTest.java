package com.magmaguy.resourcepackmanager.mixer.engine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverlayMetadataMergeTest {

    @Test
    void mixedOverlayMetadataIsNormalizedBeforePublication(@TempDir Path tempDir) throws Exception {
        Path higherPriorityPack = createPack(tempDir.resolve("higher.zip"), """
                {
                  "pack": {"pack_format": 65},
                  "overlays": {"entries": [
                    {
                      "directory": "legacy_assets",
                      "formats": {"min_inclusive": 46, "max_inclusive": 64}
                    }
                  ]}
                }
                """);
        Path lowerPriorityPack = createPack(tempDir.resolve("lower.zip"), """
                {
                  "pack": {"pack_format": 75},
                  "overlays": {"entries": [
                    {"directory": "stellarity_assets", "min_format": 65, "max_format": 75}
                  ]}
                }
                """);
        RecordingLogger logger = new RecordingLogger();
        MixOutput output = runMix(tempDir, logger, higherPriorityPack, lowerPriorityPack);

        JsonObject mcmeta = readJson(output.mergedDir().toPath().resolve("pack.mcmeta"));
        JsonObject stellarityEntry = overlayEntry(mcmeta, "stellarity_assets");
        assertTrue(stellarityEntry.has("formats"),
                () -> "Published mixed-generation overlays without the legacy formats field; warnings="
                        + logger.warnings);
        JsonObject formats = stellarityEntry.getAsJsonObject("formats");
        assertEquals(65, formats.get("min_inclusive").getAsInt());
        assertEquals(75, formats.get("max_inclusive").getAsInt());

        JsonObject legacyEntry = overlayEntry(mcmeta, "legacy_assets");
        assertEquals(46, legacyEntry.get("min_format").getAsInt());
        assertEquals(64, legacyEntry.get("max_format").getAsInt());
        assertEquals(46, legacyEntry.getAsJsonObject("formats").get("min_inclusive").getAsInt());
        assertEquals(64, legacyEntry.getAsJsonObject("formats").get("max_inclusive").getAsInt());
    }

    @Test
    void newFormatOnlyOverlayDoesNotPublishRemovedFormatsField(@TempDir Path tempDir) throws Exception {
        Path pack = createPack(tempDir.resolve("new-format.zip"), """
                {
                  "pack": {"min_format": 65, "max_format": 75},
                  "overlays": {"entries": [
                    {
                      "directory": "new_assets",
                      "formats": {"min_inclusive": 65, "max_inclusive": 75},
                      "min_format": 65,
                      "max_format": 75
                    }
                  ]}
                }
                """);

        MixOutput output = runMix(tempDir, new RecordingLogger(), pack);

        JsonObject entry = overlayEntry(readJson(output.mergedDir().toPath().resolve("pack.mcmeta")), "new_assets");
        assertFalse(entry.has("formats"), "Resource-pack format 65+ forbids the removed formats field");
        assertEquals(65, entry.get("min_format").getAsInt());
        assertEquals(75, entry.get("max_format").getAsInt());
    }

    @Test
    void fullMinorVersionBoundsRemainValid(@TempDir Path tempDir) throws Exception {
        Path pack = createPack(tempDir.resolve("minor-versions.zip"), """
                {
                  "pack": {"min_format": [65, 1], "max_format": [75, 2]},
                  "overlays": {"entries": [
                    {
                      "directory": "minor_assets",
                      "min_format": [65, 1],
                      "max_format": [75, 2]
                    }
                  ]}
                }
                """);

        MixOutput output = runMix(tempDir, new RecordingLogger(), pack);

        JsonObject entry = overlayEntry(readJson(output.mergedDir().toPath().resolve("pack.mcmeta")), "minor_assets");
        assertEquals(1, entry.getAsJsonArray("min_format").get(1).getAsInt());
        assertEquals(2, entry.getAsJsonArray("max_format").get(1).getAsInt());
        assertFalse(entry.has("formats"));
    }

    @Test
    void unsafeOverlayDirectoryStopsPublication(@TempDir Path tempDir) throws Exception {
        Path pack = createPack(tempDir.resolve("unsafe-directory.zip"), """
                {
                  "pack": {"min_format": 65, "max_format": 75},
                  "overlays": {"entries": [
                    {"directory": "../outside", "min_format": 65, "max_format": 75}
                  ]}
                }
                """);

        var failure = assertThrows(java.io.IOException.class,
                () -> runMix(tempDir, new RecordingLogger(), pack));

        assertTrue(failure.getMessage().contains("directory"), failure::getMessage);
        assertFalse(tempDir.resolve("output/merged.zip").toFile().exists());
    }

    @Test
    void overlaySectionWithoutEntriesStopsPublication(@TempDir Path tempDir) throws Exception {
        Path pack = createPack(tempDir.resolve("missing-entries.zip"), """
                {
                  "pack": {"min_format": 65, "max_format": 75},
                  "overlays": {}
                }
                """);

        var failure = assertThrows(java.io.IOException.class,
                () -> runMix(tempDir, new RecordingLogger(), pack));

        assertTrue(failure.getMessage().contains("entries"), failure::getMessage);
        assertFalse(tempDir.resolve("output/merged.zip").toFile().exists());
    }

    @Test
    void unrepresentableOverlayStopsPublication(@TempDir Path tempDir) throws Exception {
        Path invalidPack = createPack(tempDir.resolve("invalid.zip"), """
                {
                  "pack": {"pack_format": 75},
                  "overlays": {"entries": [
                    {
                      "directory": "broken_assets",
                      "formats": {"min_inclusive": 65}
                    }
                  ]}
                }
                """);
        RecordingLogger logger = new RecordingLogger();

        var failure = assertThrows(java.io.IOException.class,
                () -> runMix(tempDir, logger, invalidPack));

        assertTrue(failure.getMessage().contains("broken_assets"), failure::getMessage);
        assertFalse(tempDir.resolve("output/merged.zip").toFile().exists(),
                "Invalid overlay metadata reached the published zip");
    }

    private static MixOutput runMix(Path tempDir, RecordingLogger logger, Path... packs) throws Exception {
        Path output = tempDir.resolve("output");
        return new MixEngine(logger, () -> false).run(new MixInput(
                List.of(packs).stream().map(Path::toFile).toList(),
                tempDir.resolve("working").toFile(),
                output.toFile(),
                tempDir.resolve("logs").toFile(),
                "merged",
                false));
    }

    private static Path createPack(Path root, String packMcmeta) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(root))) {
            zip.putNextEntry(new ZipEntry("pack.mcmeta"));
            zip.write(packMcmeta.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return root;
    }

    private static JsonObject readJson(Path path) throws Exception {
        try (var reader = Files.newBufferedReader(path)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static JsonObject overlayEntry(JsonObject mcmeta, String directory) {
        return mcmeta.getAsJsonObject("overlays").getAsJsonArray("entries").asList().stream()
                .map(element -> element.getAsJsonObject())
                .filter(entry -> directory.equals(entry.get("directory").getAsString()))
                .findFirst()
                .orElseThrow();
    }

    private static final class RecordingLogger implements MixerLogger {
        private final List<String> warnings = new ArrayList<>();

        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message) {
            warnings.add(message);
        }

        @Override
        public void collision(String message) {
        }
    }
}
