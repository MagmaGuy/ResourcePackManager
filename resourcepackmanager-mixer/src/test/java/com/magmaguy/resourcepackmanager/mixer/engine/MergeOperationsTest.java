package com.magmaguy.resourcepackmanager.mixer.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Safety-net unit tests for {@link MergeOperations}. These lock in the JSON merge semantics
 * so future refactors can be done with confidence that the behaviour didn't drift.
 */
class MergeOperationsTest {

    private MergeOperations newOps() {
        return new MergeOperations(new MixerLogger() {
            @Override public void info(String m) {}
            @Override public void warn(String m) {}
            @Override public void collision(String m) {}
        });
    }

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    // ------------------------------------------------------------------
    // mergeJsonObjects
    // ------------------------------------------------------------------

    @Test
    void mergeJsonObjects_disjointKeys_preservesBoth() {
        JsonObject a = json("{\"x\":1}");
        JsonObject b = json("{\"y\":2}");
        JsonObject result = newOps().mergeJsonObjects(a, b);
        assertEquals(1, result.get("x").getAsInt());
        assertEquals(2, result.get("y").getAsInt());
    }

    @Test
    void mergeJsonObjects_conflictingScalar_targetWins() {
        // In mergeJsonObjects(json1, json2), json2 (target / higher-priority) wins on scalar conflicts.
        JsonObject source = json("{\"k\":\"source\"}");
        JsonObject target = json("{\"k\":\"target\"}");
        JsonObject result = newOps().mergeJsonObjects(source, target);
        assertEquals("target", result.get("k").getAsString());
    }

    @Test
    void mergeJsonObjects_nestedObjects_recurses() {
        JsonObject a = json("{\"outer\":{\"a\":1,\"shared\":\"sourceVal\"}}");
        JsonObject b = json("{\"outer\":{\"b\":2,\"shared\":\"targetVal\"}}");
        JsonObject result = newOps().mergeJsonObjects(a, b);
        JsonObject outer = result.getAsJsonObject("outer");
        assertEquals(1, outer.get("a").getAsInt());
        assertEquals(2, outer.get("b").getAsInt());
        // scalar conflict inside nested → target wins
        assertEquals("targetVal", outer.get("shared").getAsString());
    }

    // ------------------------------------------------------------------
    // mergePackMcmeta
    // ------------------------------------------------------------------

    @Test
    void mergePackMcmeta_packFormat_takesMax(@TempDir Path tempDir) throws IOException {
        File source = tempDir.resolve("source.json").toFile();
        File target = tempDir.resolve("target.json").toFile();

        // target higher
        Files.writeString(source.toPath(), "{\"pack\":{\"pack_format\":15}}");
        Files.writeString(target.toPath(), "{\"pack\":{\"pack_format\":34}}");
        newOps().mergePackMcmeta(source, target);
        JsonObject result = JsonParser.parseString(Files.readString(target.toPath())).getAsJsonObject();
        assertEquals(34, result.getAsJsonObject("pack").get("pack_format").getAsInt());

        // source higher
        Files.writeString(source.toPath(), "{\"pack\":{\"pack_format\":55}}");
        Files.writeString(target.toPath(), "{\"pack\":{\"pack_format\":34}}");
        newOps().mergePackMcmeta(source, target);
        JsonObject result2 = JsonParser.parseString(Files.readString(target.toPath())).getAsJsonObject();
        assertEquals(55, result2.getAsJsonObject("pack").get("pack_format").getAsInt());
    }

    @Test
    void mergePackMcmeta_minMaxFormat_widensRange(@TempDir Path tempDir) throws IOException {
        File source = tempDir.resolve("source.json").toFile();
        File target = tempDir.resolve("target.json").toFile();

        // source: 50..60, target: 55..65 → expect 50..65
        Files.writeString(source.toPath(),
                "{\"pack\":{\"pack_format\":55,\"min_format\":50,\"max_format\":60}}");
        Files.writeString(target.toPath(),
                "{\"pack\":{\"pack_format\":60,\"min_format\":55,\"max_format\":65}}");
        newOps().mergePackMcmeta(source, target);

        JsonObject result = JsonParser.parseString(Files.readString(target.toPath())).getAsJsonObject();
        JsonObject pack = result.getAsJsonObject("pack");
        assertEquals(50, pack.get("min_format").getAsInt());
        assertEquals(65, pack.get("max_format").getAsInt());
    }

    @Test
    void mergePackMcmeta_newFormatRangeKeepsOldPackFormatValid(@TempDir Path tempDir) throws IOException {
        File source = tempDir.resolve("source.json").toFile();
        File target = tempDir.resolve("target.json").toFile();

        Files.writeString(source.toPath(),
                "{\"pack\":{\"description\":\"new\",\"min_format\":65,\"max_format\":84}}");
        Files.writeString(target.toPath(),
                "{\"pack\":{\"description\":\"old\",\"pack_format\":34}}");
        newOps().mergePackMcmeta(source, target);

        JsonObject result = JsonParser.parseString(Files.readString(target.toPath())).getAsJsonObject();
        JsonObject pack = result.getAsJsonObject("pack");
        assertEquals(34, pack.get("pack_format").getAsInt());
        assertEquals(34, pack.get("min_format").getAsInt());
        assertEquals(84, pack.get("max_format").getAsInt());
        JsonArray supportedFormats = pack.getAsJsonArray("supported_formats");
        assertEquals(34, supportedFormats.get(0).getAsInt());
        assertEquals(64, supportedFormats.get(1).getAsInt());
    }

    @Test
    void mergePackMcmeta_newOnlyRangeReplacesStalePackFormat(@TempDir Path tempDir) throws IOException {
        File source = tempDir.resolve("source.json").toFile();
        File target = tempDir.resolve("target.json").toFile();

        Files.writeString(source.toPath(),
                "{\"pack\":{\"description\":\"source\",\"min_format\":65,\"max_format\":84}}");
        Files.writeString(target.toPath(),
                "{\"pack\":{\"description\":\"target\",\"pack_format\":34,\"min_format\":65,\"max_format\":84,\"supported_formats\":[65,84]}}");
        newOps().mergePackMcmeta(source, target);

        JsonObject result = JsonParser.parseString(Files.readString(target.toPath())).getAsJsonObject();
        JsonObject pack = result.getAsJsonObject("pack");
        assertEquals(65, pack.get("pack_format").getAsInt());
        assertEquals(65, pack.get("min_format").getAsInt());
        assertEquals(84, pack.get("max_format").getAsInt());
        assertFalse(pack.has("supported_formats"));
    }

    @Test
    void mergePackMcmeta_overlayEntries_combinedDeduplicatedByDirectory(@TempDir Path tempDir) throws IOException {
        File source = tempDir.resolve("source.json").toFile();
        File target = tempDir.resolve("target.json").toFile();

        // target has "alpha"; source has "alpha" (dup) + "beta" (new).
        Files.writeString(target.toPath(), "{"
                + "\"pack\":{\"pack_format\":34},"
                + "\"overlays\":{\"entries\":["
                + "  {\"directory\":\"alpha\",\"formats\":[34,42],\"min_format\":34,\"max_format\":42}"
                + "]}}");
        Files.writeString(source.toPath(), "{"
                + "\"pack\":{\"pack_format\":34},"
                + "\"overlays\":{\"entries\":["
                + "  {\"directory\":\"alpha\",\"formats\":[1,1],\"min_format\":1,\"max_format\":1},"
                + "  {\"directory\":\"beta\",\"formats\":[34,42],\"min_format\":34,\"max_format\":42}"
                + "]}}");
        newOps().mergePackMcmeta(source, target);

        JsonObject result = JsonParser.parseString(Files.readString(target.toPath())).getAsJsonObject();
        JsonArray entries = result.getAsJsonObject("overlays").getAsJsonArray("entries");
        assertEquals(2, entries.size(), "Duplicate directory should be dropped");

        // The target's alpha entry should still be the one we kept (the alpha[0]=34 one).
        JsonObject alpha = null;
        JsonObject beta = null;
        for (JsonElement e : entries) {
            JsonObject obj = e.getAsJsonObject();
            String dir = obj.get("directory").getAsString();
            if (dir.equals("alpha")) alpha = obj;
            else if (dir.equals("beta")) beta = obj;
        }
        assertNotNull(alpha, "alpha must remain");
        assertNotNull(beta, "beta must have been added from source");
        // confirm we kept target's alpha (min_format=34), not source's (min_format=1)
        assertEquals(34, alpha.get("min_format").getAsInt());
    }

    @Test
    void mergePackMcmeta_overlayEntries_normalizesMissingMinMaxFormat(@TempDir Path tempDir) throws IOException {
        // Regression for 1.21.9+: overlay entries with `formats` but no `min_format`/`max_format`
        // must be backfilled, or the client rejects the pack.
        File source = tempDir.resolve("source.json").toFile();
        File target = tempDir.resolve("target.json").toFile();

        // target has an overlay entry with `formats` array but no min_format / max_format.
        Files.writeString(target.toPath(), "{"
                + "\"pack\":{\"pack_format\":34},"
                + "\"overlays\":{\"entries\":["
                + "  {\"directory\":\"legacy\",\"formats\":[34,65]}"
                + "]}}");
        // Need a source with overlays too so the entries get reprocessed/normalized.
        Files.writeString(source.toPath(), "{"
                + "\"pack\":{\"pack_format\":34},"
                + "\"overlays\":{\"entries\":["
                + "  {\"directory\":\"extra\",\"formats\":{\"min_inclusive\":34,\"max_inclusive\":65}}"
                + "]}}");
        newOps().mergePackMcmeta(source, target);

        JsonObject result = JsonParser.parseString(Files.readString(target.toPath())).getAsJsonObject();
        JsonArray entries = result.getAsJsonObject("overlays").getAsJsonArray("entries");
        for (JsonElement e : entries) {
            JsonObject obj = e.getAsJsonObject();
            assertTrue(obj.has("min_format"),
                    "Expected normalized min_format on overlay entry " + obj);
            assertTrue(obj.has("max_format"),
                    "Expected normalized max_format on overlay entry " + obj);
        }
    }

    // ------------------------------------------------------------------
    // mergePackMcmeta supported_formats — covers parseSupportedFormatsRange
    // (renamed from parseSupportedFormatsRange_allThreeShapes_parseCorrectly:
    //  the private parser is exercised via the public mergePackMcmeta path.)
    // ------------------------------------------------------------------

    @Test
    void mergePackMcmeta_supportedFormats_allThreeShapesWiden(@TempDir Path tempDir) throws IOException {
        MergeOperations ops = newOps();

        // Case A: int shape vs 2-int array shape. source=10 (treated as [10,10]),
        // target=[20,30]. Expected merged range: [10, 30].
        File s1 = tempDir.resolve("a-source.json").toFile();
        File t1 = tempDir.resolve("a-target.json").toFile();
        Files.writeString(s1.toPath(), "{\"pack\":{\"pack_format\":34,\"supported_formats\":10}}");
        Files.writeString(t1.toPath(), "{\"pack\":{\"pack_format\":34,\"supported_formats\":[20,30]}}");
        ops.mergePackMcmeta(s1, t1);
        JsonObject r1 = JsonParser.parseString(Files.readString(t1.toPath())).getAsJsonObject();
        JsonArray sf1 = r1.getAsJsonObject("pack").getAsJsonArray("supported_formats");
        assertEquals(10, sf1.get(0).getAsInt());
        assertEquals(30, sf1.get(1).getAsInt());

        // Case B: object shape vs 2-int array shape.
        // source={"min_inclusive":5,"max_inclusive":12}, target=[20,30] → [5, 30].
        File s2 = tempDir.resolve("b-source.json").toFile();
        File t2 = tempDir.resolve("b-target.json").toFile();
        Files.writeString(s2.toPath(), "{\"pack\":{\"pack_format\":34,\"supported_formats\":{\"min_inclusive\":5,\"max_inclusive\":12}}}");
        Files.writeString(t2.toPath(), "{\"pack\":{\"pack_format\":34,\"supported_formats\":[20,30]}}");
        ops.mergePackMcmeta(s2, t2);
        JsonObject r2 = JsonParser.parseString(Files.readString(t2.toPath())).getAsJsonObject();
        JsonArray sf2 = r2.getAsJsonObject("pack").getAsJsonArray("supported_formats");
        assertEquals(5, sf2.get(0).getAsInt());
        assertEquals(30, sf2.get(1).getAsInt());

        // Case C: object shape vs int shape.
        // source=int 7, target={"min_inclusive":10,"max_inclusive":20} → [7, 20].
        File s3 = tempDir.resolve("c-source.json").toFile();
        File t3 = tempDir.resolve("c-target.json").toFile();
        Files.writeString(s3.toPath(), "{\"pack\":{\"pack_format\":34,\"supported_formats\":7}}");
        Files.writeString(t3.toPath(), "{\"pack\":{\"pack_format\":34,\"supported_formats\":{\"min_inclusive\":10,\"max_inclusive\":20}}}");
        ops.mergePackMcmeta(s3, t3);
        JsonObject r3 = JsonParser.parseString(Files.readString(t3.toPath())).getAsJsonObject();
        JsonArray sf3 = r3.getAsJsonObject("pack").getAsJsonArray("supported_formats");
        assertEquals(7, sf3.get(0).getAsInt());
        assertEquals(20, sf3.get(1).getAsInt());
    }

    @Test
    void sanitizeMergedModels_addsParticleTextureAndClampsUvs(@TempDir Path tempDir) throws IOException {
        Path model = tempDir.resolve("assets/example/models/block/test.json");
        Files.createDirectories(model.getParent());
        Files.writeString(model, "{"
                + "\"textures\":{\"0\":\"example:block/test\"},"
                + "\"elements\":[{\"faces\":{\"north\":{\"uv\":[-0.5,0,16.25,17],\"texture\":\"#0\"}}}]"
                + "}");

        newOps().sanitizeMergedModels(tempDir.toFile());

        JsonObject result = JsonParser.parseString(Files.readString(model)).getAsJsonObject();
        assertEquals("example:block/test", result.getAsJsonObject("textures").get("particle").getAsString());
        JsonArray uv = result.getAsJsonArray("elements")
                .get(0).getAsJsonObject()
                .getAsJsonObject("faces")
                .getAsJsonObject("north")
                .getAsJsonArray("uv");
        assertEquals(0.0, uv.get(0).getAsDouble());
        assertEquals(0.0, uv.get(1).getAsDouble());
        assertEquals(16.0, uv.get(2).getAsDouble());
        assertEquals(16.0, uv.get(3).getAsDouble());
    }

    // ------------------------------------------------------------------
    // mergeSoundsJson
    // ------------------------------------------------------------------

    @Test
    void mergeSoundsJson_replaceTrue_replacesEvent() {
        JsonObject source = json("{"
                + "\"entity.zombie.ambient\":{\"sounds\":[\"source/a\",\"source/b\"]}"
                + "}");
        JsonObject target = json("{"
                + "\"entity.zombie.ambient\":{\"replace\":true,\"sounds\":[\"target/x\"]}"
                + "}");
        JsonObject merged = newOps().mergeSoundsJson(source, target);

        JsonObject event = merged.getAsJsonObject("entity.zombie.ambient");
        JsonArray sounds = event.getAsJsonArray("sounds");
        assertEquals(1, sounds.size(), "replace=true should drop source sounds");
        assertEquals("target/x", sounds.get(0).getAsString());
    }

    @Test
    void mergeSoundsJson_replaceFalseOrMissing_concatenatesSoundsArray() {
        JsonObject source = json("{"
                + "\"entity.zombie.ambient\":{\"sounds\":[\"source/a\",\"source/b\"]}"
                + "}");
        // No `replace` key on target → should default to concat (replace=false).
        JsonObject target = json("{"
                + "\"entity.zombie.ambient\":{\"sounds\":[\"target/x\"]}"
                + "}");
        JsonObject merged = newOps().mergeSoundsJson(source, target);

        JsonObject event = merged.getAsJsonObject("entity.zombie.ambient");
        JsonArray sounds = event.getAsJsonArray("sounds");
        assertEquals(3, sounds.size());
        // Source first (lower priority), then target.
        assertEquals("source/a", sounds.get(0).getAsString());
        assertEquals("source/b", sounds.get(1).getAsString());
        assertEquals("target/x", sounds.get(2).getAsString());
    }

    // ------------------------------------------------------------------
    // mergeItemsModels
    // ------------------------------------------------------------------

    @Test
    void mergeItemsModels_rangeDispatch_combinesEntriesSortedByThreshold() {
        JsonObject source = json("{"
                + "\"model\":{"
                + "  \"type\":\"range_dispatch\","
                + "  \"property\":\"custom_model_data\","
                + "  \"entries\":["
                + "    {\"threshold\":5,\"model\":{\"type\":\"model\",\"model\":\"src/five\"}},"
                + "    {\"threshold\":15,\"model\":{\"type\":\"model\",\"model\":\"src/fifteen\"}}"
                + "  ]"
                + "}}");
        JsonObject target = json("{"
                + "\"model\":{"
                + "  \"type\":\"range_dispatch\","
                + "  \"property\":\"custom_model_data\","
                + "  \"entries\":["
                + "    {\"threshold\":10,\"model\":{\"type\":\"model\",\"model\":\"tgt/ten\"}}"
                + "  ]"
                + "}}");
        JsonObject merged = newOps().mergeItemsModels(source, target);
        JsonArray entries = merged.getAsJsonObject("model").getAsJsonArray("entries");

        assertEquals(3, entries.size());
        assertEquals(5,  entries.get(0).getAsJsonObject().get("threshold").getAsInt());
        assertEquals(10, entries.get(1).getAsJsonObject().get("threshold").getAsInt());
        assertEquals(15, entries.get(2).getAsJsonObject().get("threshold").getAsInt());
    }

    @Test
    void mergeItemsModels_select_concatenatesCasesDedupedByWhen() {
        JsonObject source = json("{"
                + "\"model\":{"
                + "  \"type\":\"select\","
                + "  \"property\":\"trim_material\","
                + "  \"cases\":["
                + "    {\"when\":\"iron\",\"model\":{\"type\":\"model\",\"model\":\"src/iron\"}},"
                + "    {\"when\":\"gold\",\"model\":{\"type\":\"model\",\"model\":\"src/gold\"}}"
                + "  ]"
                + "}}");
        JsonObject target = json("{"
                + "\"model\":{"
                + "  \"type\":\"select\","
                + "  \"property\":\"trim_material\","
                + "  \"cases\":["
                + "    {\"when\":\"iron\",\"model\":{\"type\":\"model\",\"model\":\"tgt/iron\"}},"
                + "    {\"when\":\"diamond\",\"model\":{\"type\":\"model\",\"model\":\"tgt/diamond\"}}"
                + "  ]"
                + "}}");
        JsonObject merged = newOps().mergeItemsModels(source, target);
        JsonArray cases = merged.getAsJsonObject("model").getAsJsonArray("cases");

        // 3 unique `when` keys: iron, gold, diamond — iron must be deduped.
        assertEquals(3, cases.size());

        // Locate iron and confirm TARGET wins (later put-into-map call).
        boolean foundIron = false, foundGold = false, foundDiamond = false;
        for (JsonElement e : cases) {
            JsonObject c = e.getAsJsonObject();
            String when = c.get("when").getAsString();
            if (when.equals("iron")) {
                foundIron = true;
                assertEquals("tgt/iron",
                        c.getAsJsonObject("model").get("model").getAsString(),
                        "Target should win on `when` collision");
            } else if (when.equals("gold")) {
                foundGold = true;
            } else if (when.equals("diamond")) {
                foundDiamond = true;
            }
        }
        assertTrue(foundIron && foundGold && foundDiamond);
    }

    @Test
    void mergeItemsModels_mismatchedModelTypes_targetWinsAtomically() {
        // source = range_dispatch, target = composite → can't merge, target returned untouched.
        JsonObject source = json("{"
                + "\"model\":{"
                + "  \"type\":\"range_dispatch\","
                + "  \"property\":\"custom_model_data\","
                + "  \"entries\":[{\"threshold\":1,\"model\":{\"type\":\"model\",\"model\":\"src/one\"}}]"
                + "}}");
        JsonObject target = json("{"
                + "\"model\":{"
                + "  \"type\":\"composite\","
                + "  \"models\":[{\"type\":\"model\",\"model\":\"tgt/composite\"}]"
                + "}}");
        JsonObject merged = newOps().mergeItemsModels(source, target);
        JsonObject model = merged.getAsJsonObject("model");
        assertEquals("composite", model.get("type").getAsString());
        // Atomic: target's composite.models must be unchanged, NOT mixed with source entries.
        JsonArray models = model.getAsJsonArray("models");
        assertEquals(1, models.size());
        assertEquals("tgt/composite", models.get(0).getAsJsonObject().get("model").getAsString());
    }

    // ------------------------------------------------------------------
    // isMergeableJsonFile
    // ------------------------------------------------------------------

    @Test
    void isMergeableJsonFile_modelsItemJson_returnsTrueForLegacyOverrideMerge() {
        File f = new File("anywhere/assets/minecraft/models/item/shield.json");
        assertTrue(newOps().isMergeableJsonFile(f));
    }

    @Test
    void mergeLegacyItemModelOverrides_combinesOverridesWithoutCorruptingDisplayArrays() {
        JsonObject source = json("""
                {
                  "parent": "minecraft:item/handheld",
                  "display": {
                    "gui": {
                      "translation": [1, 2, 3]
                    }
                  },
                  "overrides": [
                    {
                      "predicate": { "custom_model_data": 10 },
                      "model": "source:item/ten"
                    },
                    {
                      "predicate": { "custom_model_data": 20 },
                      "model": "source:item/twenty"
                    }
                  ]
                }
                """);
        JsonObject target = json("""
                {
                  "parent": "minecraft:item/handheld",
                  "display": {
                    "gui": {
                      "translation": [4, 5, 6]
                    }
                  },
                  "overrides": [
                    {
                      "predicate": { "custom_model_data": 20 },
                      "model": "target:item/twenty"
                    },
                    {
                      "predicate": { "custom_model_data": 30 },
                      "model": "target:item/thirty"
                    }
                  ]
                }
                """);

        JsonObject merged = newOps().mergeLegacyItemModelOverrides(source, target);

        JsonArray translation = merged.getAsJsonObject("display")
                .getAsJsonObject("gui")
                .getAsJsonArray("translation");
        assertEquals(3, translation.size(), "fixed-length display arrays must not be concatenated");
        assertEquals(4, translation.get(0).getAsInt());

        JsonArray overrides = merged.getAsJsonArray("overrides");
        assertEquals(3, overrides.size());
        assertTrue(overrides.toString().contains("source:item/ten"));
        assertTrue(overrides.toString().contains("target:item/twenty"));
        assertTrue(overrides.toString().contains("target:item/thirty"));
        assertFalse(overrides.toString().contains("source:item/twenty"), "higher-priority target CMD wins");
    }

    // ------------------------------------------------------------------
    // warnOnInvalidOverlayMetadata
    // ------------------------------------------------------------------

    private static MergeOperations newOps(java.util.List<String> warnings) {
        return new MergeOperations(new MixerLogger() {
            @Override public void info(String m) {}
            @Override public void warn(String m) { warnings.add(m); }
            @Override public void collision(String m) {}
        });
    }

    @Test
    void warnOnInvalidOverlayMetadata_lowRangeMissingFormats_warnsNamingOverlay(@TempDir Path tempDir) throws IOException {
        // Overlay dips below the 64/65 boundary (min_format=34) but has no `formats` field →
        // MC 1.21.9+ clients reject the pack. WARN expected, file must stay untouched.
        Path mcmeta = tempDir.resolve("pack.mcmeta");
        String original = "{"
                + "\"pack\":{\"pack_format\":34},"
                + "\"overlays\":{\"entries\":["
                + "  {\"directory\":\"legacy_overlay\",\"min_format\":34,\"max_format\":84}"
                + "]}}";
        Files.writeString(mcmeta, original);

        java.util.List<String> warnings = new java.util.ArrayList<>();
        newOps(warnings).warnOnInvalidOverlayMetadata(tempDir.toFile());

        assertEquals(1, warnings.size(), "Exactly one overlay should be flagged");
        assertTrue(warnings.get(0).contains("legacy_overlay"), "Warning must name the offending overlay");
        assertTrue(warnings.get(0).contains("formats"), "Warning must mention the missing formats field");
        // WARN ONLY: the file must not have been modified.
        assertEquals(original, Files.readString(mcmeta), "warnOnInvalidOverlayMetadata must not rewrite the file");
    }

    @Test
    void warnOnInvalidOverlayMetadata_validFormats_doesNotWarn(@TempDir Path tempDir) throws IOException {
        // Overlay dips below the boundary but carries a valid `formats` field → no warning.
        Path mcmeta = tempDir.resolve("pack.mcmeta");
        Files.writeString(mcmeta, "{"
                + "\"pack\":{\"pack_format\":34},"
                + "\"overlays\":{\"entries\":["
                + "  {\"directory\":\"legacy_overlay\",\"min_format\":34,\"max_format\":84,\"formats\":[34,84]}"
                + "]}}");

        java.util.List<String> warnings = new java.util.ArrayList<>();
        newOps(warnings).warnOnInvalidOverlayMetadata(tempDir.toFile());

        assertTrue(warnings.isEmpty(), "Valid formats field should not trigger a warning: " + warnings);
    }

    @Test
    void warnOnInvalidOverlayMetadata_rangeAboveBoundary_doesNotWarn(@TempDir Path tempDir) throws IOException {
        // Overlay range is entirely 65+ (does not dip below the boundary) → `formats` not required.
        Path mcmeta = tempDir.resolve("pack.mcmeta");
        Files.writeString(mcmeta, "{"
                + "\"pack\":{\"pack_format\":65},"
                + "\"overlays\":{\"entries\":["
                + "  {\"directory\":\"new_overlay\",\"min_format\":65,\"max_format\":84}"
                + "]}}");

        java.util.List<String> warnings = new java.util.ArrayList<>();
        newOps(warnings).warnOnInvalidOverlayMetadata(tempDir.toFile());

        assertTrue(warnings.isEmpty(), "Range entirely above the boundary needs no formats field: " + warnings);
    }

    @Test
    void isMergeableJsonFile_assetsItemsJson_returnsTrue() {
        File f = new File("anywhere/assets/minecraft/items/iron_sword.json");
        assertTrue(newOps().isMergeableJsonFile(f));
    }

    @Test
    void isMergeableJsonFile_atlasJson_returnsTrue() {
        File f = new File("anywhere/assets/minecraft/atlases/blocks.json");
        assertTrue(newOps().isMergeableJsonFile(f));
    }
}
