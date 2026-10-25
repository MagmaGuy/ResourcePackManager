package com.magmaguy.resourcepackmanager.bedrock;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.magmaguy.resourcepackmanager.mixer.engine.MixerLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SUPERSHOOT (#patron-support): Oraxen + CustomCrops crops showed as plain apples on
 * Bedrock. Oraxen's item definitions carry no material, so the converter mapped the
 * crops under its paper/stick/sword guesses and never under apple.
 */
@EnabledIfEnvironmentVariable(named = "NIGHTBREAK_TEMP_TICKET_TESTS", matches = "true")
final class OraxenItemBasesTicketTest {

    private static final List<String> GUESSED_BASES = List.of(
            "minecraft:paper", "minecraft:stick", "minecraft:name_tag", "minecraft:compass",
            "minecraft:wooden_sword", "minecraft:stone_sword", "minecraft:iron_sword",
            "minecraft:golden_sword", "minecraft:diamond_sword", "minecraft:netherite_sword");

    @TempDir
    Path temp;

    @Test
    void oraxenConfigsDeclareTheMaterialOraxenBuildsEachItemOn() throws IOException {
        Map<String, List<String>> declared = OraxenItemBases.read(writeOraxenConfigs());

        assertEquals(new TreeMap<>(Map.of(
                "oraxen:tomato", List.of("minecraft:apple"),
                "oraxen:eggplant", List.of("minecraft:apple"),
                "oraxen:seed_bag", List.of("minecraft:paper"),
                "oraxen:watering_can", List.of("minecraft:paper"),
                "oraxen:sprinkler", List.of("minecraft:iron_ingot"),
                "oraxen:sprinkler/watering", List.of("minecraft:iron_ingot"),
                "dews:sprinkler", List.of("minecraft:iron_ingot"))), new TreeMap<>(declared));
    }

    @Test
    void cropsMapUnderTheirMaterialAndKeepEveryGuessedBase() throws IOException {
        Map<String, Set<String>> bases = convert(OraxenItemBases.read(writeOraxenConfigs()));

        for (String crop : List.of("oraxen:tomato", "oraxen:eggplant")) {
            assertTrue(bases.get(crop).contains("minecraft:apple"), crop + " bases: " + bases.get(crop));
            assertTrue(bases.get(crop).containsAll(GUESSED_BASES), crop + " bases: " + bases.get(crop));
        }
        assertEquals(new TreeSet<>(GUESSED_BASES), bases.get("oraxen:seed_bag"));
    }

    @Test
    void withoutDeclarationsCropsHaveNoAppleMapping() throws IOException {
        writeOraxenConfigs();
        Map<String, Set<String>> bases = convert(Map.of());

        assertFalse(bases.get("oraxen:tomato").contains("minecraft:apple"), "bases: " + bases.get("oraxen:tomato"));
        assertEquals(new TreeSet<>(GUESSED_BASES), bases.get("oraxen:tomato"));
    }

    private File writeOraxenConfigs() throws IOException {
        Path oraxen = temp.resolve("Oraxen");
        write(oraxen.resolve("items/farming/crops.yml"), """
                fruit_template:
                  template: true
                  material: APPLE
                tomato:
                  material: apple
                  Pack:
                    generate_model: true
                    textures:
                      - item/tomato
                eggplant:
                  template: fruit_template
                  pack:
                    generate_model: true
                    textures:
                      - item/eggplant
                seed_bag:
                  Pack:
                    generate_model: true
                    textures:
                      - item/seed_bag
                watering_can:
                  material: NOT_A_MATERIAL
                  Pack:
                    generate_model: true
                    textures:
                      - item/watering_can
                """);
        // Templates are global: this item is skipped because a template owns its key.
        write(oraxen.resolve("items/extra.yml"), """
                fruit_template:
                  material: STONE
                sprinkler:
                  material: IRON_INGOT
                  Pack:
                    model: custom/sprinkler
                    models:
                      watering: custom/sprinkler_on
                  Components:
                    item_model: "dews:sprinkler"
                """);
        write(oraxen.resolve("items/broken.yml"), "tomato: [unterminated\n");
        return oraxen.toFile();
    }

    /** @return Geyser mapping bases per Java item model after a real conversion */
    private Map<String, Set<String>> convert(Map<String, List<String>> declared) throws IOException {
        Path pack = temp.resolve("pack-" + declared.size());
        write(pack.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":84,\"description\":\"oraxen\"}}");
        for (String item : List.of("tomato", "eggplant", "seed_bag", "watering_can")) {
            Path assets = pack.resolve("assets/oraxen");
            write(assets.resolve("items/" + item + ".json"),
                    "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"oraxen:item/" + item + "\"}}");
            write(assets.resolve("models/item/" + item + ".json"),
                    "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"oraxen:item/" + item + "\"}}");
            Path texture = assets.resolve("textures/item/" + item + ".png");
            Files.createDirectories(texture.getParent());
            Files.write(texture, png16());
        }
        File out = temp.resolve("out-" + declared.size()).toFile();
        assertTrue(out.mkdirs());

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

    private static void write(Path file, String content) throws IOException {
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
        java.io.ByteArrayOutputStream compressed = new java.io.ByteArrayOutputStream();
        try (DeflaterOutputStream deflate = new DeflaterOutputStream(compressed, new Deflater())) {
            deflate.write(raw);
        }
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        png.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
        chunk(png, "IHDR", new byte[]{0, 0, 0, 16, 0, 0, 0, 16, 8, 6, 0, 0, 0});
        chunk(png, "IDAT", compressed.toByteArray());
        chunk(png, "IEND", new byte[0]);
        return png.toByteArray();
    }

    private static void chunk(java.io.ByteArrayOutputStream png, String type, byte[] data) throws IOException {
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
