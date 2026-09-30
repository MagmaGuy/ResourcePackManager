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
    void collidingLegacyAndMinorVersionPacksPreserveExactModernMaximum(@TempDir Path tempDir) throws Exception {
        Path legacy = createPack(tempDir.resolve("legacy.zip"), """
                {"pack":{"pack_format":46,"supported_formats":[46,64]}}
                """);
        Path modern = createPack(tempDir.resolve("modern.zip"), """
                {"pack":{"min_format":[97,0],"max_format":[97,1]},
                 "overlays":{"entries":[{"directory":"modern","min_format":[97,1],"max_format":[97,1]}]}}
                """);

        JsonObject result = readJson(runMix(tempDir, new RecordingLogger(), legacy, modern)
                .mergedDir().toPath().resolve("pack.mcmeta"));
        JsonObject pack = result.getAsJsonObject("pack");
        assertEquals(46, pack.get("min_format").getAsInt());
        assertEquals(JsonParser.parseString("[97,1]"), pack.get("max_format"));
        assertEquals(JsonParser.parseString("[46,64]"), pack.get("supported_formats"));
        assertEquals(46, pack.get("pack_format").getAsInt());
        assertEquals(JsonParser.parseString("[97,1]"), overlayEntry(result, "modern").get("min_format"));
        assertEquals(JsonParser.parseString("[97,1]"), overlayEntry(result, "modern").get("max_format"));
    }

    @Test
    void collidingMinorVersionPacksCompareBothComponents(@TempDir Path tempDir) throws Exception {
        Path earlier = createPack(tempDir.resolve("earlier.zip"), """
                {"pack":{"min_format":[97,1],"max_format":[97,2]}}
                """);
        Path later = createPack(tempDir.resolve("later.zip"), """
                {"pack":{"min_format":[97,3],"max_format":[97,10]}}
                """);

        JsonObject pack = readJson(runMix(tempDir, new RecordingLogger(), later, earlier)
                .mergedDir().toPath().resolve("pack.mcmeta")).getAsJsonObject("pack");
        assertEquals(JsonParser.parseString("[97,1]"), pack.get("min_format"));
        assertEquals(JsonParser.parseString("[97,10]"), pack.get("max_format"));
        assertFalse(pack.has("supported_formats"));
    }

    @Test
    void integerAndSingleElementUpperBoundsIncludeEveryMinorVersion(@TempDir Path tempDir) throws Exception {
        Path broad = createPack(tempDir.resolve("broad.zip"), """
                {"pack":{"min_format":[97],"max_format":[97]}}
                """);
        Path exact = createPack(tempDir.resolve("exact.zip"), """
                {"pack":{"min_format":[97,1],"max_format":[97,1]}}
                """);

        JsonObject pack = readJson(runMix(tempDir, new RecordingLogger(), exact, broad)
                .mergedDir().toPath().resolve("pack.mcmeta")).getAsJsonObject("pack");
        assertEquals(97, pack.get("min_format").getAsInt());
        assertEquals(97, pack.get("max_format").getAsInt());
    }

    @Test
    void exactZeroMinorMaximumDoesNotBecomeUnbounded(@TempDir Path tempDir) throws Exception {
        Path earlier = createPack(tempDir.resolve("earlier.zip"), """
                {"pack":{"min_format":75,"max_format":88}}
                """);
        Path exact = createPack(tempDir.resolve("exact.zip"), """
                {"pack":{"min_format":[97,0],"max_format":[97,0]}}
                """);

        JsonObject pack = readJson(runMix(tempDir, new RecordingLogger(), exact, earlier)
                .mergedDir().toPath().resolve("pack.mcmeta")).getAsJsonObject("pack");
        assertEquals(75, pack.get("min_format").getAsInt());
        assertEquals(JsonParser.parseString("[97,0]"), pack.get("max_format"));
    }

    @Test
    void legacyRangeFormsRetainTheirRangeMeaningDuringCollision(@TempDir Path tempDir) throws Exception {
        Path scalar = createPack(tempDir.resolve("scalar.zip"), """
                {"pack":{"pack_format":46,"supported_formats":46}}
                """);
        Path array = createPack(tempDir.resolve("array.zip"), """
                {"pack":{"pack_format":55,"supported_formats":[55,63]}}
                """);
        Path object = createPack(tempDir.resolve("object.zip"), """
                {"pack":{"pack_format":64,"supported_formats":{"min_inclusive":63,"max_inclusive":64}}}
                """);

        JsonObject pack = readJson(runMix(tempDir, new RecordingLogger(), scalar, array, object)
                .mergedDir().toPath().resolve("pack.mcmeta")).getAsJsonObject("pack");
        assertEquals(JsonParser.parseString("[46,64]"), pack.get("supported_formats"));
        assertEquals(46, pack.get("pack_format").getAsInt());
        assertFalse(pack.has("min_format"));
        assertFalse(pack.has("max_format"));
    }

    @Test
    void normalizedModernMetadataDoesNotRegainObsoleteSourceFields(@TempDir Path tempDir) throws Exception {
        Path higher = createPack(tempDir.resolve("higher.zip"), """
                {"pack":{"min_format":75,"max_format":88,"description":"keep higher priority"}}
                """);
        Path lower = createPack(tempDir.resolve("lower.zip"), """
                {"pack":{"min_format":[97,0],"max_format":[97,1],"supported_formats":[75,97],"custom":true}}
                """);

        JsonObject pack = readJson(runMix(tempDir, new RecordingLogger(), higher, lower)
                .mergedDir().toPath().resolve("pack.mcmeta")).getAsJsonObject("pack");
        assertFalse(pack.has("supported_formats"));
        assertEquals(JsonParser.parseString("[97,1]"), pack.get("max_format"));
        assertEquals("keep higher priority", pack.get("description").getAsString());
        assertTrue(pack.get("custom").getAsBoolean());
    }

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
    void combinedLegacyOverlayListGivesEveryEntryAFormatsRange(@TempDir Path tempDir) throws Exception {
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
        // Minecraft rejects the whole overlay list when one entry reaches format 64 or lower and
        // another lacks formats, although each source list is valid on its own.
        JsonObject stellarityEntry = overlayEntry(mcmeta, "stellarity_assets");
        JsonObject formats = stellarityEntry.getAsJsonObject("formats");
        assertEquals(65, formats.get("min_inclusive").getAsInt());
        assertEquals(75, formats.get("max_inclusive").getAsInt());
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
