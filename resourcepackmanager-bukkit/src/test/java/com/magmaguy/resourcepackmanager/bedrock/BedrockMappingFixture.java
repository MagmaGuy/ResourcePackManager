package com.magmaguy.resourcepackmanager.bedrock;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.magmaguy.resourcepackmanager.mixer.engine.MixerLogger;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs a real Bedrock conversion of a small generated-item pack and reads its Geyser mappings. */
final class BedrockMappingFixture {

    /** The bases the converter guesses for an item model when nothing declares one. */
    static final List<String> GUESSED_BASES = List.of(
            "minecraft:paper", "minecraft:stick", "minecraft:name_tag", "minecraft:compass",
            "minecraft:wooden_sword", "minecraft:stone_sword", "minecraft:iron_sword",
            "minecraft:golden_sword", "minecraft:diamond_sword", "minecraft:netherite_sword");

    private BedrockMappingFixture() {
    }

    /**
     * Converts a pack with one textured {@code namespace:item} definition per item.
     *
     * @return Geyser mapping bases per Java item model
     */
    static Map<String, Set<String>> convert(Path temp, String namespace, List<String> items,
                                            Map<String, List<String>> declared) throws IOException {
        Path pack = Files.createTempDirectory(temp, "pack-");
        write(pack.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":84,\"description\":\"" + namespace + "\"}}");
        for (String item : items) {
            Path assets = pack.resolve("assets/" + namespace);
            write(assets.resolve("items/" + item + ".json"),
                    "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + namespace + ":item/" + item + "\"}}");
            write(assets.resolve("models/item/" + item + ".json"),
                    "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"" + namespace + ":item/" + item + "\"}}");
            Path texture = assets.resolve("textures/item/" + item + ".png");
            Files.createDirectories(texture.getParent());
            Files.write(texture, png16());
        }
        File out = Files.createTempDirectory(temp, "out-").toFile();

        MixerLogger silent = new MixerLogger() {
            @Override public void info(String message) { }
            @Override public void warn(String message) { }
            @Override public void collision(String message) { }
        };
        BedrockConverterContext context = new BedrockConverterContext() {
            @Override public MixerLogger logger() { return silent; }
            @Override public String pluginVersion() { return "ticket"; }
            @Override public boolean isBedrockTargetPresent() { return true; }
            @Override public boolean isBedrockConversionEnabled() { return true; }
            @Override public void deployMappingsIfNeeded(File mappingsFile) { }
            @Override public File previousMappingsFile() { return null; }
            @Override public Map<String, List<String>> declaredBaseItems() { return declared; }
        };
        assertTrue(BedrockConversion.generate(pack.toFile(), out, context));

        JsonObject mappings = JsonParser.parseString(Files.readString(
                new File(out, BedrockConversion.GEYSER_MAPPINGS_NAME).toPath())).getAsJsonObject();
        Map<String, Set<String>> bases = new TreeMap<>();
        for (Map.Entry<String, JsonElement> base : mappings.getAsJsonObject("items").entrySet())
            for (JsonElement definition : base.getValue().getAsJsonArray())
                bases.computeIfAbsent(definition.getAsJsonObject().get("model").getAsString(),
                        model -> new TreeSet<>()).add(base.getKey());
        return bases;
    }

    static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static byte[] png16() throws IOException {
        byte[] raw = new byte[16 * (1 + 16 * 4)];
        for (int row = 0; row < 16; row++)
            for (int x = 0; x < 16; x++) {
                int pixel = row * (1 + 16 * 4) + 1 + x * 4;
                raw[pixel] = (byte) 200;
                raw[pixel + 3] = (byte) 255;
            }
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflate = new DeflaterOutputStream(compressed, new Deflater())) {
            deflate.write(raw);
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
        chunk(png, "IHDR", new byte[]{0, 0, 0, 16, 0, 0, 0, 16, 8, 6, 0, 0, 0});
        chunk(png, "IDAT", compressed.toByteArray());
        chunk(png, "IEND", new byte[0]);
        return png.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream png, String type, byte[] data) throws IOException {
        png.write(new byte[]{(byte) (data.length >>> 24), (byte) (data.length >>> 16), (byte) (data.length >>> 8), (byte) data.length});
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        png.write(typeBytes);
        png.write(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        long value = crc.getValue();
        png.write(new byte[]{(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value});
    }
}
