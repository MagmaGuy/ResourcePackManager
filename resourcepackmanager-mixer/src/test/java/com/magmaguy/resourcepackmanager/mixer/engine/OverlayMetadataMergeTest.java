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
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverlayMetadataMergeTest {

    @Test
    void singlePackMetadataIsCopiedWithoutRewriting(@TempDir Path tempDir) throws Exception {
        String metadata = "{\n  \"pack\": {\"min_format\": 42, \"max_format\": 87, \"description\": \"author supplied\"},\n"
                + "  \"overlays\": {\"entries\": [{\"directory\": \"Overlay-Author\",\n"
                + "    \"formats\": {\"min_inclusive\": 43, \"max_inclusive\": 64},\n"
                + "    \"min_format\": 42, \"max_format\": 87, \"custom\": {\"keep\": true}}]}\n}\n";
        Path pack = createPack(tempDir.resolve("single.zip"), metadata);

        MixOutput output = runMix(tempDir, new RecordingLogger(), pack);
        Path copied = output.mergedDir().toPath().resolve("pack.mcmeta");
        assertArrayEquals(metadata.getBytes(java.nio.charset.StandardCharsets.UTF_8), Files.readAllBytes(copied));
    }

    @Test
    void mixedOverlayMetadataPreservesEachEntry(@TempDir Path tempDir) throws Exception {
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
        MixOutput output = runMix(tempDir, new RecordingLogger(), higherPriorityPack, lowerPriorityPack);

        JsonObject mcmeta = readJson(output.mergedDir().toPath().resolve("pack.mcmeta"));
        JsonObject stellarityEntry = overlayEntry(mcmeta, "stellarity_assets");
        assertFalse(stellarityEntry.has("formats"));
        assertEquals(65, stellarityEntry.get("min_format").getAsInt());
        assertEquals(75, stellarityEntry.get("max_format").getAsInt());

        JsonObject legacyEntry = overlayEntry(mcmeta, "legacy_assets");
        assertEquals(46, legacyEntry.getAsJsonObject("formats").get("min_inclusive").getAsInt());
        assertEquals(64, legacyEntry.getAsJsonObject("formats").get("max_inclusive").getAsInt());
        assertFalse(legacyEntry.has("min_format"));
        assertFalse(legacyEntry.has("max_format"));
    }

    @Test
    void suppliedFormatsFieldIsPreservedForNewFormatOverlay(@TempDir Path tempDir) throws Exception {
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
        assertTrue(entry.has("formats"), "The mixer must preserve an author's supplied field");
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
    void legacyFormatsMayCoverOnlyPreMinorClients(@TempDir Path tempDir) throws Exception {
        Path pack = createPack(tempDir.resolve("mixed-legacy-range.zip"), """
                {
                  "pack": {"min_format": 42, "max_format": 87},
                  "overlays": {"entries": [
                    {
                      "directory": "ia_overlay_1_21_2_plus",
                      "formats": {"min_inclusive": 42, "max_inclusive": 64},
                      "min_format": 42,
                      "max_format": 87
                    }
                  ]}
                }
                """);

        MixOutput output = runMix(tempDir, new RecordingLogger(), pack);

        JsonObject entry = overlayEntry(readJson(output.mergedDir().toPath().resolve("pack.mcmeta")),
                "ia_overlay_1_21_2_plus");
        assertEquals(42, entry.getAsJsonObject("formats").get("min_inclusive").getAsInt());
        assertEquals(64, entry.getAsJsonObject("formats").get("max_inclusive").getAsInt());
        assertEquals(42, entry.get("min_format").getAsInt());
        assertEquals(87, entry.get("max_format").getAsInt());
    }

    @Test
    void missingLegacyFieldIsNotSynthesized(@TempDir Path tempDir) throws Exception {
        Path pack = createPack(tempDir.resolve("synthesized-legacy-range.zip"), """
                {
                  "pack": {"min_format": 42, "max_format": 87},
                  "overlays": {"entries": [
                    {"directory": "legacy_overlay", "min_format": 42, "max_format": 87}
                  ]}
                }
                """);

        MixOutput output = runMix(tempDir, new RecordingLogger(), pack);

        JsonObject entry = overlayEntry(readJson(output.mergedDir().toPath().resolve("pack.mcmeta")),
                "legacy_overlay");
        assertFalse(entry.has("formats"));
        assertEquals(42, entry.get("min_format").getAsInt());
        assertEquals(87, entry.get("max_format").getAsInt());
    }

    @Test
    void mismatchedLegacyRangesRemainAuthorsResponsibility(@TempDir Path tempDir) throws Exception {
        Path pack = createPack(tempDir.resolve("mismatched-legacy-range.zip"), """
                {
                  "pack": {"min_format": 42, "max_format": 87},
                  "overlays": {"entries": [
                    {
                      "directory": "invalid_overlay",
                      "formats": {"min_inclusive": 43, "max_inclusive": 64},
                      "min_format": 42,
                      "max_format": 87
                    }
                  ]}
                }
                """);

        MixOutput output = runMix(tempDir, new RecordingLogger(), pack);
        JsonObject entry = overlayEntry(readJson(output.mergedDir().toPath().resolve("pack.mcmeta")),
                "invalid_overlay");
        assertEquals(43, entry.getAsJsonObject("formats").get("min_inclusive").getAsInt());
        assertEquals(42, entry.get("min_format").getAsInt());
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

        MixOutput output = runMix(tempDir, new RecordingLogger(), pack);
        assertTrue(output.mergedDir().toPath().resolve("pack.mcmeta").toFile().exists());
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

        MixOutput output = runMix(tempDir, logger, invalidPack);
        JsonObject entry = overlayEntry(readJson(output.mergedDir().toPath().resolve("pack.mcmeta")),
                "broken_assets");
        assertTrue(entry.getAsJsonObject("formats").has("min_inclusive"));
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
