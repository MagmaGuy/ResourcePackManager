package com.magmaguy.resourcepackmanager.bedrock;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.magmaguy.resourcepackmanager.bedrock.converter.SoundDefinitionConverter;
import com.magmaguy.resourcepackmanager.mixer.engine.MixEngine;
import com.magmaguy.resourcepackmanager.mixer.engine.MixInput;
import com.magmaguy.resourcepackmanager.mixer.engine.MixOutput;
import com.magmaguy.resourcepackmanager.mixer.engine.MixerLogger;
import com.magmaguy.resourcepackmanager.mixer.engine.internal.AsyncDirectoryCleaner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TEMPORARY (2026-11-05): runs the opt-in Bedrock custom sound conversion of cc61fff through
 * {@link BedrockConversion} and the mixer's bedrockSoundExcludedPacks merge. The commit after the one that adds this
 * file retires it; it stays retrievable from history.
 */
final class BedrockSoundConversionTest {

    private static final MixerLogger SILENT = new MixerLogger() {
        @Override public void info(String message) { }
        @Override public void warn(String message) { }
        @Override public void collision(String message) { }
    };
    private static final String DEFINITIONS = SoundDefinitionConverter.DEFINITIONS_PATH;

    @TempDir
    Path temp;

    @Test
    void aPackWithOnlyCustomSoundsIsPublished() throws Exception {
        Path pack = temp.resolve("pack");
        mcmeta(pack);
        byte[] ding = dingSounds(pack);

        File out = Files.createDirectories(temp.resolve("out")).toFile();
        Context context = new Context(true);
        assertTrue(BedrockConversion.generate(pack.toFile(), out, context));
        awaitCleanup(out.toPath());

        File zip = new File(out, BedrockConversion.BEDROCK_PACK_NAME + ".zip");
        assertTrue(zip.isFile(), "a sounds-only pack is not treated as empty");
        try (ZipFile archive = new ZipFile(zip)) {
            JsonObject definitions = definitions(archive);
            assertEquals(Set.of("server:effect.ding"), definitions.keySet());
            assertArrayEquals(ding, bytes(archive, "sounds/server/effects/ding.ogg"));
            assertNotNull(archive.getEntry("manifest.json"));
        }
        assertFalse(new File(out, BedrockConversion.GEYSER_MAPPINGS_NAME).exists(), "no items, no mappings");
        assertEquals(1, context.converted.size(), "the platform hears what the conversion added");
        assertEquals(1, context.converted.get(0).events());
        assertEquals(1, context.converted.get(0).files());
        assertEquals(ding.length, context.converted.get(0).bytes());
    }

    @Test
    void withSoundConversionOffNoSoundReachesBedrock() throws Exception {
        // Items and sounds: the items convert, the sounds stay out.
        Path pack = temp.resolve("pack");
        mcmeta(pack);
        ruby(pack);
        dingSounds(pack);
        File out = Files.createDirectories(temp.resolve("out")).toFile();
        Context context = new Context(false);
        assertTrue(BedrockConversion.generate(pack.toFile(), out, context));
        awaitCleanup(out.toPath());

        try (ZipFile archive = new ZipFile(new File(out, BedrockConversion.BEDROCK_PACK_NAME + ".zip"))) {
            List<String> soundEntries = Collections.list(archive.entries()).stream()
                    .map(ZipEntry::getName).filter(name -> name.startsWith("sounds/")).toList();
            assertEquals(List.of(), soundEntries, "no sound files or definitions");
        }
        assertTrue(mappedModels(out).contains("custom:ruby"), "the item still converts");
        assertEquals(List.of(), context.converted, "soundsConverted is not called with the setting off");

        // Sounds alone: nothing to convert, so no Bedrock pack at all.
        Path soundsOnly = temp.resolve("sounds-only");
        mcmeta(soundsOnly);
        dingSounds(soundsOnly);
        File soundsOut = Files.createDirectories(temp.resolve("sounds-out")).toFile();
        assertTrue(BedrockConversion.generate(soundsOnly.toFile(), soundsOut, new Context(false)));
        awaitCleanup(soundsOut.toPath());
        assertFalse(new File(soundsOut, BedrockConversion.BEDROCK_PACK_NAME + ".zip").exists());
    }

    @Test
    void aSoundExcludedPackKeepsItsItemsButNotItsSounds() throws Exception {
        // Both packs define sounds in the same namespace, so the merged sounds.json holds both.
        Path songsDir = temp.resolve("songs");
        mcmeta(songsDir);
        ruby(songsDir);
        write(songsDir.resolve("assets/server/sounds.json"),
                "{\"song.battle\": {\"sounds\": [{\"name\": \"server:songs/battle\", \"stream\": true}]}}");
        ogg(songsDir, "server", "songs/battle", 30000);
        Path effectsDir = temp.resolve("effects");
        mcmeta(effectsDir);
        byte[] ding = dingSounds(effectsDir);
        File songs = zip(songsDir, temp.resolve("songs.zip"));
        File effects = zip(effectsDir, temp.resolve("effects.zip"));

        File mixDir = temp.resolve("mix").toFile();
        MixOutput mix = new MixEngine(SILENT, () -> false).run(new MixInput(List.of(songs, effects), mixDir, mixDir,
                temp.resolve("logs").toFile(), "mixed", false, null, Set.of(), true, Set.of(songs)));

        // Java keeps every sound; the converter reads sounds from a sounds-only merge without the songs pack.
        String javaSounds = Files.readString(new File(mix.mergedDir(), "assets/server/sounds.json").toPath());
        assertTrue(javaSounds.contains("song.battle") && javaSounds.contains("effect.ding"), javaSounds);
        assertEquals(mix.mergedDir(), mix.bedrockInputDir(), "items convert from the full merge");
        assertNotEquals(mix.bedrockInputDir(), mix.bedrockSoundInputDir());
        String bedrockSounds = Files.readString(new File(mix.bedrockSoundInputDir(), "assets/server/sounds.json").toPath());
        assertTrue(bedrockSounds.contains("effect.ding") && !bedrockSounds.contains("song.battle"), bedrockSounds);
        assertFalse(new File(mix.bedrockSoundInputDir(), "assets/custom").exists(), "the sound input holds only sounds");
        assertEquals(Map.of("assets/server/sounds/effects/ding.ogg", "effects.zip"), mix.bedrockSoundSources());

        File out = Files.createDirectories(temp.resolve("out")).toFile();
        Context context = new Context(true);
        assertTrue(BedrockConversion.generate(mix.bedrockInputDir(),
                new BedrockSoundInput(mix.bedrockSoundInputDir(), mix.bedrockSoundSources()), out, context));
        awaitCleanup(out.toPath());
        awaitCleanup(mixDir.toPath());

        assertTrue(mappedModels(out).contains("custom:ruby"), "the songs pack's item still converts");
        try (ZipFile archive = new ZipFile(new File(out, BedrockConversion.BEDROCK_PACK_NAME + ".zip"))) {
            assertEquals(Set.of("server:effect.ding"), definitions(archive).keySet(), "the other pack's sound remains");
            assertNull(archive.getEntry("sounds/server/songs/battle.ogg"), "the songs pack's file stays out");
            assertArrayEquals(ding, bytes(archive, "sounds/server/effects/ding.ogg"));
        }
        assertEquals(Map.of("effects.zip", new SoundDefinitionConverter.SourceTotals(1, ding.length)),
                context.converted.get(0).bySource(), "totals name the source pack");
    }

    @Test
    void withoutSoundExclusionsTheConverterReadsSoundsFromItsInput() throws Exception {
        Path effectsDir = temp.resolve("effects");
        mcmeta(effectsDir);
        dingSounds(effectsDir);
        File effects = zip(effectsDir, temp.resolve("effects.zip"));

        File mixDir = temp.resolve("mix").toFile();
        MixOutput mix = new MixEngine(SILENT, () -> false).run(new MixInput(List.of(effects), mixDir, mixDir,
                temp.resolve("logs").toFile(), "mixed", false, null, Set.of(), true, Set.of()));
        awaitCleanup(mixDir.toPath());

        assertEquals(mix.bedrockInputDir(), mix.bedrockSoundInputDir());
        assertEquals(Map.of("assets/server/sounds/effects/ding.ogg", "effects.zip"), mix.bedrockSoundSources());
        assertFalse(new File(mixDir, "mixed" + MixEngine.BEDROCK_SOUND_INPUT_SUFFIX).exists());
    }

    private static final class Context implements BedrockConverterContext {
        private final boolean sounds;
        private final List<SoundDefinitionConverter.Result> converted = new ArrayList<>();

        private Context(boolean sounds) {
            this.sounds = sounds;
        }

        @Override public MixerLogger logger() { return SILENT; }
        @Override public String pluginVersion() { return "sound-test"; }
        @Override public boolean isBedrockTargetPresent() { return true; }
        @Override public boolean isBedrockConversionEnabled() { return true; }
        @Override public void deployMappingsIfNeeded(File mappingsFile) { }
        @Override public File previousMappingsFile() { return null; }
        @Override public Map<String, List<String>> declaredBaseItems() { return Map.of(); }
        @Override public boolean isBedrockSoundConversionEnabled() { return sounds; }
        @Override public void soundsConverted(SoundDefinitionConverter.Result result) { converted.add(result); }
    }

    private static JsonObject definitions(ZipFile archive) throws IOException {
        JsonObject root = JsonParser.parseString(new String(bytes(archive, DEFINITIONS), StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals("1.20.20", root.get("format_version").getAsString());
        return root.getAsJsonObject("sound_definitions");
    }

    private static byte[] bytes(ZipFile archive, String name) throws IOException {
        ZipEntry entry = archive.getEntry(name);
        assertNotNull(entry, name + " in the Bedrock pack");
        return archive.getInputStream(entry).readAllBytes();
    }

    private static Set<String> mappedModels(File out) throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(
                new File(out, BedrockConversion.GEYSER_MAPPINGS_NAME).toPath())).getAsJsonObject();
        Set<String> models = new java.util.TreeSet<>();
        for (Map.Entry<String, JsonElement> base : root.getAsJsonObject("items").entrySet())
            for (JsonElement definition : base.getValue().getAsJsonArray())
                models.add(definition.getAsJsonObject().get("model").getAsString());
        return models;
    }

    private static void mcmeta(Path pack) throws IOException {
        write(pack.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":84,\"description\":\"sounds\"}}");
    }

    /** assets/server/sounds.json with the one event server:effect.ding and its file. */
    private static byte[] dingSounds(Path pack) throws IOException {
        write(pack.resolve("assets/server/sounds.json"),
                "{\"effect.ding\": {\"sounds\": [\"server:effects/ding\"]}}");
        return ogg(pack, "server", "effects/ding", 1200);
    }

    private static void ruby(Path pack) throws IOException {
        write(pack.resolve("assets/custom/items/ruby.json"),
                "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"custom:item/ruby\"}}");
        write(pack.resolve("assets/custom/models/item/ruby.json"),
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"custom:item/ruby\"}}");
        Path texture = pack.resolve("assets/custom/textures/item/ruby.png");
        Files.createDirectories(texture.getParent());
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 16; x++)
            for (int y = 0; y < 16; y++)
                image.setRGB(x, y, 0xFFC81E32);
        ImageIO.write(image, "png", texture.toFile());
    }

    private static byte[] ogg(Path pack, String namespace, String path, int size) throws IOException {
        byte[] bytes = new byte[size];
        new Random((namespace + ":" + path).hashCode()).nextBytes(bytes);
        Path file = pack.resolve("assets/" + namespace + "/sounds/" + path + ".ogg");
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
        return bytes;
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static File zip(Path dir, Path zipFile) throws IOException {
        List<Path> files;
        try (Stream<Path> stream = Files.walk(dir)) {
            files = stream.filter(Files::isRegularFile).sorted().toList();
        }
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            for (Path file : files) {
                zip.putNextEntry(new ZipEntry(dir.relativize(file).toString().replace('\\', '/')));
                zip.write(Files.readAllBytes(file));
                zip.closeEntry();
            }
        }
        return zipFile.toFile();
    }

    /** The converter and mixer reclaim staging folders in the background; let that finish before JUnit deletes the tree. */
    private static void awaitCleanup(Path directory) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            boolean trash;
            try (Stream<Path> children = Files.list(directory)) {
                trash = children.anyMatch(path ->
                        path.getFileName().toString().startsWith(AsyncDirectoryCleaner.TRASH_PREFIX));
            }
            if (!trash) return;
            assertTrue(System.nanoTime() < deadline, "staging cleanup did not finish");
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }
    }
}
