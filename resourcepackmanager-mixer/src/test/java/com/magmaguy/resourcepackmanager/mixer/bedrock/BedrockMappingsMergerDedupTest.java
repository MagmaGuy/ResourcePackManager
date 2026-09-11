package com.magmaguy.resourcepackmanager.mixer.bedrock;

import com.google.gson.JsonArray;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces the merged-mappings collision behind Geyser's boot error "conflicts with
 * custom item definition (...): both entries have the same predicates": two backends
 * emitting the same (item, model, predicates) matcher under different bedrock
 * identifiers, which the identifier-level dedup cannot collapse.
 */
class BedrockMappingsMergerDedupTest {

    @TempDir
    Path tempDir;

    private final List<String> logLines = new ArrayList<>();
    private final MixerLogger logger = new MixerLogger() {
        @Override public void info(String message) { logLines.add(message); }
        @Override public void warn(String message) { logLines.add(message); }
        @Override public void collision(String message) { logLines.add(message); }
    };

    private static JsonObject cmdDefinition(String id, String model, double cmd) {
        JsonObject predicate = new JsonObject();
        predicate.addProperty("type", "range_dispatch");
        predicate.addProperty("property", "custom_model_data");
        predicate.addProperty("threshold", cmd);
        predicate.addProperty("scale", 1.0);
        predicate.addProperty("index", 0);
        JsonArray predicates = new JsonArray();
        predicates.add(predicate);
        JsonObject def = new JsonObject();
        def.addProperty("type", "definition");
        def.addProperty("bedrock_identifier", id);
        def.add("predicate", predicates);
        def.addProperty("model", model);
        return def;
    }

    private static JsonObject legacyDefinition(String id, double cmd) {
        JsonObject def = new JsonObject();
        def.addProperty("type", "legacy");
        def.addProperty("custom_model_data", cmd);
        def.addProperty("bedrock_identifier", id);
        return def;
    }

    private File writeMappings(String name, String baseItem, JsonObject... definitions) throws IOException {
        JsonArray array = new JsonArray();
        for (JsonObject def : definitions) array.add(def);
        JsonObject items = new JsonObject();
        items.add(baseItem, array);
        JsonObject root = new JsonObject();
        root.addProperty("format_version", 2);
        root.add("items", items);
        Path file = tempDir.resolve(name);
        Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
        return file.toFile();
    }

    private JsonArray mergedEntries(File merged, String baseItem) throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(merged.toPath(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        return root.getAsJsonObject("items").getAsJsonArray(baseItem);
    }

    @Test
    void samePredicatePairAcrossBackendsCollapsesToLastWriter() throws IOException {
        File backend0 = writeMappings("backend0.json", "minecraft:paper",
                cmdDefinition("r:aaaaaaaa", "minecraft:paper", 101));
        File backend1 = writeMappings("backend1.json", "minecraft:paper",
                cmdDefinition("r:bbbbbbbb", "minecraft:paper", 101));

        File merged = new BedrockMappingsMerger(logger)
                .merge(List.of(backend0, backend1), tempDir.resolve("merged.json").toFile());

        assertNotNull(merged);
        JsonArray entries = mergedEntries(merged, "minecraft:paper");
        assertEquals(1, entries.size());
        assertEquals("r:bbbbbbbb",
                entries.get(0).getAsJsonObject().get("bedrock_identifier").getAsString());
        assertTrue(logLines.stream().anyMatch(line -> line.contains("Deduplicated 1")
                        && line.contains("r:bbbbbbbb") && line.contains("r:aaaaaaaa")),
                "expected a one-line dedup summary naming both identifiers, got: " + logLines);
    }

    @Test
    void modernDefinitionBeatsLegacyForTheSameMatcher() throws IOException {
        File backend0 = writeMappings("backend0.json", "minecraft:paper",
                cmdDefinition("r:aaaaaaaa", "minecraft:paper", 101));
        File backend1 = writeMappings("backend1.json", "minecraft:paper",
                legacyDefinition("r:bbbbbbbb", 101));

        File merged = new BedrockMappingsMerger(logger)
                .merge(List.of(backend0, backend1), tempDir.resolve("merged.json").toFile());

        assertNotNull(merged);
        JsonArray entries = mergedEntries(merged, "minecraft:paper");
        assertEquals(1, entries.size());
        assertEquals("r:aaaaaaaa",
                entries.get(0).getAsJsonObject().get("bedrock_identifier").getAsString());
    }

    @Test
    void distinctMatchersSurviveUntouched() throws IOException {
        File backend0 = writeMappings("backend0.json", "minecraft:paper",
                cmdDefinition("r:aaaaaaaa", "minecraft:paper", 101));
        File backend1 = writeMappings("backend1.json", "minecraft:paper",
                cmdDefinition("r:bbbbbbbb", "minecraft:paper", 102));

        File merged = new BedrockMappingsMerger(logger)
                .merge(List.of(backend0, backend1), tempDir.resolve("merged.json").toFile());

        assertNotNull(merged);
        assertEquals(2, mergedEntries(merged, "minecraft:paper").size());
        assertTrue(logLines.stream().noneMatch(line -> line.contains("Deduplicated")),
                "no dedup summary expected, got: " + logLines);
    }
}
