package com.magmaguy.resourcepackmanager.bedrock.converter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.magmaguy.resourcepackmanager.bedrock.BedrockLog;
import com.magmaguy.resourcepackmanager.mixer.engine.MixerLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TEMPORARY (2026-11-05): checks the opt-in Bedrock custom sound conversion of cc61fff. The commit after the one that
 * adds this file retires it; it stays retrievable from history.
 */
final class SoundDefinitionConverterTest {

    private final List<String> warnings = new CopyOnWriteArrayList<>();

    @TempDir
    Path temp;

    @BeforeEach
    void captureWarnings() {
        BedrockLog.set(new MixerLogger() {
            @Override public void info(String message) { }
            @Override public void warn(String message) { warnings.add(message); }
            @Override public void collision(String message) { }
        });
    }

    @AfterEach
    void resetLog() {
        BedrockLog.set(null);
    }

    @Test
    void customSoundEventsBecomeBedrockDefinitions() throws Exception {
        Path java = temp.resolve("java");
        write(java.resolve("assets/server/sounds.json"), """
                {
                  "effect.ding": {"sounds": ["server:effects/ding",
                      {"name": "server:effects/dong", "volume": 0.5, "pitch": 1.5, "weight": 3, "attenuation_distance": 24}]},
                  "song.battle": {"sounds": [{"name": "server:songs/battle", "stream": true, "attenuation_distance": 64}]},
                  "mix.random": {"sounds": [
                      {"name": "server:effect.ding", "type": "event", "volume": 0.5, "pitch": 2.0, "weight": 7},
                      "server:effects/extra"]},
                  "partly.missing": {"sounds": ["server:effects/missing", "server:effects/ding"]},
                  "all.missing": {"sounds": ["server:effects/gone"]}
                }
                """);
        byte[] ding = ogg(java, "server", "effects/ding", 1500);
        byte[] dong = ogg(java, "server", "effects/dong", 2600);
        byte[] battle = ogg(java, "server", "songs/battle", 40000);
        byte[] extra = ogg(java, "server", "effects/extra", 700);
        // A vanilla replacement whose file is present: the minecraft namespace stays out.
        write(java.resolve("assets/minecraft/sounds.json"),
                "{\"block.note_block.harp\": {\"replace\": true, \"sounds\": [\"minecraft:custom/harp\"]}}");
        ogg(java, "minecraft", "custom/harp", 900);

        Path bedrock = temp.resolve("bedrock");
        Map<String, String> sources = Map.of(
                "assets/server/sounds/effects/ding.ogg", "effects.zip",
                "assets/server/sounds/songs/battle.ogg", "songs.zip");
        SoundDefinitionConverter.Result result =
                SoundDefinitionConverter.convert(java.toFile(), bedrock.toFile(), sources, () -> false);

        String raw = Files.readString(bedrock.resolve(SoundDefinitionConverter.DEFINITIONS_PATH));
        JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
        assertEquals("1.20.20", root.get("format_version").getAsString());
        JsonObject definitions = root.getAsJsonObject("sound_definitions");
        // Keys are the full Java event ids; the minecraft namespace and the event with no file are absent.
        assertEquals(Set.of("server:effect.ding", "server:song.battle", "server:mix.random", "server:partly.missing"),
                definitions.keySet());

        // A string entry keeps only its name; an object entry keeps volume, pitch and weight. No .ogg extension.
        JsonObject effect = definitions.getAsJsonObject("server:effect.ding");
        assertEquals("neutral", effect.get("category").getAsString());
        assertEquals(json("""
                [{"name": "sounds/server/effects/ding"},
                 {"name": "sounds/server/effects/dong", "volume": 0.5, "pitch": 1.5, "weight": 3}]
                """), effect.get("sounds"));
        assertEquals("24.0", effect.get("max_distance").getAsString(), "attenuation_distance becomes a float");

        // A streamed sound makes the event a record.
        JsonObject song = definitions.getAsJsonObject("server:song.battle");
        assertEquals("record", song.get("category").getAsString());
        assertEquals("64.0", song.get("max_distance").getAsString());
        assertEquals(json("[{\"name\": \"sounds/server/songs/battle\", \"stream\": true}]"), song.get("sounds"));
        assertTrue(raw.matches("(?s).*\"max_distance\":\\s*64\\.0.*"), "max_distance written as a float: " + raw);

        // The reference is inlined: the referenced weights stay (the reference's own weight 7 does not),
        // and volume and pitch multiply. The largest attenuation of the inlined sounds carries over.
        JsonObject mix = definitions.getAsJsonObject("server:mix.random");
        assertEquals("neutral", mix.get("category").getAsString());
        assertEquals(json("""
                [{"name": "sounds/server/effects/ding", "volume": 0.5, "pitch": 2.0},
                 {"name": "sounds/server/effects/dong", "volume": 0.25, "pitch": 3.0, "weight": 3},
                 {"name": "sounds/server/effects/extra"}]
                """), mix.get("sounds"));
        assertEquals("24.0", mix.get("max_distance").getAsString());

        // The missing file is skipped and the rest of the event converts.
        JsonObject partly = definitions.getAsJsonObject("server:partly.missing");
        assertEquals(json("[{\"name\": \"sounds/server/effects/ding\"}]"), partly.get("sounds"));
        assertFalse(partly.has("max_distance"), "no attenuation, no max_distance");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("server:effects/missing") && w.contains("server:effects/gone")),
                "one warning names the missing files: " + warnings);

        // Every file is copied once, byte for byte, under sounds/<ns>/<path>.ogg; nothing from minecraft.
        assertEquals(new TreeSet<>(Set.of(
                        "sounds/server/effects/ding.ogg", "sounds/server/effects/dong.ogg",
                        "sounds/server/effects/extra.ogg", "sounds/server/songs/battle.ogg",
                        SoundDefinitionConverter.DEFINITIONS_PATH)),
                files(bedrock));
        assertArrayEquals(ding, Files.readAllBytes(bedrock.resolve("sounds/server/effects/ding.ogg")));
        assertArrayEquals(dong, Files.readAllBytes(bedrock.resolve("sounds/server/effects/dong.ogg")));
        assertArrayEquals(battle, Files.readAllBytes(bedrock.resolve("sounds/server/songs/battle.ogg")));
        assertArrayEquals(extra, Files.readAllBytes(bedrock.resolve("sounds/server/effects/extra.ogg")));

        // Totals: events with a definition, files copied once, and the source pack of each file.
        assertEquals(4, result.events());
        assertEquals(4, result.files());
        assertEquals(1500 + 2600 + 40000 + 700, result.bytes());
        assertEquals(Map.of(
                        "effects.zip", new SoundDefinitionConverter.SourceTotals(1, 1500),
                        "songs.zip", new SoundDefinitionConverter.SourceTotals(1, 40000),
                        "assets/server", new SoundDefinitionConverter.SourceTotals(2, 2600 + 700)),
                result.bySource());
    }

    @Test
    void circularEventReferencesEndInsteadOfLooping() throws Exception {
        Path java = temp.resolve("java");
        write(java.resolve("assets/server/sounds.json"), """
                {
                  "loop.a": {"sounds": [{"name": "server:loop.b", "type": "event"}, "server:effects/ding"]},
                  "loop.b": {"sounds": [{"name": "server:loop.a", "type": "event"}]},
                  "self": {"sounds": [{"name": "server:self", "type": "event"}, "server:effects/ding"]},
                  "unresolved": {"sounds": [{"name": "server:nowhere", "type": "event"}, "server:effects/ding"]}
                }
                """);
        ogg(java, "server", "effects/ding", 800);
        Path bedrock = temp.resolve("bedrock");

        SoundDefinitionConverter.Result result = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> SoundDefinitionConverter.convert(java.toFile(), bedrock.toFile(), Map.of(), () -> false));

        JsonObject definitions = JsonParser.parseString(
                        Files.readString(bedrock.resolve(SoundDefinitionConverter.DEFINITIONS_PATH)))
                .getAsJsonObject().getAsJsonObject("sound_definitions");
        JsonElement ding = json("[{\"name\": \"sounds/server/effects/ding\"}]");
        for (String event : List.of("server:loop.a", "server:loop.b", "server:self", "server:unresolved"))
            assertEquals(ding, definitions.getAsJsonObject(event).get("sounds"), event);
        assertEquals(4, result.events());
        assertEquals(1, result.files());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("server:nowhere")),
                "the unresolved reference is reported: " + warnings);
    }

    @Test
    void vanillaSoundsAloneProduceNothing() throws Exception {
        Path java = temp.resolve("java");
        write(java.resolve("assets/minecraft/sounds.json"),
                "{\"block.note_block.harp\": {\"replace\": true, \"sounds\": [\"minecraft:custom/harp\"]}}");
        ogg(java, "minecraft", "custom/harp", 900);
        Path bedrock = temp.resolve("bedrock");

        SoundDefinitionConverter.Result result =
                SoundDefinitionConverter.convert(java.toFile(), bedrock.toFile(), Map.of(), () -> false);

        assertEquals(SoundDefinitionConverter.Result.EMPTY, result);
        assertFalse(Files.exists(bedrock.resolve("sounds")), "no sounds folder for vanilla replacements");
    }

    private static JsonElement json(String text) {
        return JsonParser.parseString(text);
    }

    private static Set<String> files(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .map(path -> root.relativize(path).toString().replace('\\', '/'))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    /** Writes assets/&lt;ns&gt;/sounds/&lt;path&gt;.ogg with distinct pseudo-random bytes and returns them. */
    private static byte[] ogg(Path pack, String namespace, String path, int size) throws IOException {
        byte[] bytes = new byte[size];
        new Random((namespace + ":" + path).hashCode()).nextBytes(bytes);
        bytes[0] = 'O';
        bytes[1] = 'g';
        bytes[2] = 'g';
        bytes[3] = 'S';
        Path file = pack.resolve("assets/" + namespace + "/sounds/" + path + ".ogg");
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
        return bytes;
    }
}
