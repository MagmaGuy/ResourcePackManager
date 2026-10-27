package com.magmaguy.resourcepackmanager.mixer.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Packs listed in bedrockExcludedPacks stay in the Java pack, while the Bedrock converter reads a merge without
 * them. Both packs add a custom item to the shared minecraft/items/paper.json, so a per-file filter would lose
 * one pack's item either way.
 */
final class BedrockExcludedPacksTest {
    private static final MixerLogger SILENT = new MixerLogger() {
        public void info(String message) { }
        public void warn(String message) { }
        public void collision(String message) { }
    };

    @Test
    void excludedPackStaysInJavaButLeavesTheBedrockInput(@TempDir Path root) throws Exception {
        File kept = pack(root, "kept", 2002, "kept:item/kept_item");
        File excluded = pack(root, "excluded", 1001, "excluded:item/excluded_item");

        MixOutput output = mix(root, List.of(excluded, kept), Set.of(excluded));

        assertNotEquals(output.mergedDir(), output.bedrockInputDir(), "the converter reads a separate merge");
        String java = paper(output.mergedDir());
        assertTrue(java.contains("excluded:item/excluded_item") && java.contains("kept:item/kept_item"),
                "the merged Java paper.json keeps both packs' items: " + java);
        try (ZipFile zip = new ZipFile(output.mergedZip())) {
            String zipped = new String(zip.getInputStream(zip.getEntry("assets/minecraft/items/paper.json")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(zipped.contains("excluded:item/excluded_item") && zipped.contains("kept:item/kept_item"),
                    "the published Java archive keeps both packs' items");
        }
        String bedrock = paper(output.bedrockInputDir());
        assertTrue(bedrock.contains("kept:item/kept_item"), "the Bedrock input keeps the other pack's item: " + bedrock);
        assertFalse(bedrock.contains("excluded:item/excluded_item"), "the Bedrock input drops the excluded pack's item");
        assertFalse(new File(output.bedrockInputDir(), "assets/excluded/models/item/excluded_item.json").exists(),
                "the excluded pack's own files are not in the Bedrock input");
    }

    @Test
    void withoutExclusionsTheConverterReadsTheJavaMerge(@TempDir Path root) throws Exception {
        File first = pack(root, "first", 1001, "first:item/first_item");
        File second = pack(root, "second", 2002, "second:item/second_item");

        MixOutput output = mix(root, List.of(first, second), Set.of());

        assertEquals(output.mergedDir(), output.bedrockInputDir());
    }

    @Test
    void excludedPackStillReachesBedrockThroughAKeptByteCopy(@TempDir Path root) throws Exception {
        File excluded = pack(root, "excluded", 1001, "excluded:item/excluded_item");
        File copy = root.resolve("copy.zip").toFile();
        Files.copy(excluded.toPath(), copy.toPath());
        File other = pack(root, "other", 2002, "other:item/other_item");

        // The copy is dropped as a duplicate of the excluded pack, but it was not excluded itself.
        MixOutput output = mix(root, List.of(excluded, other, copy), Set.of(excluded));

        String bedrock = paper(output.bedrockInputDir());
        assertTrue(bedrock.contains("excluded:item/excluded_item") && bedrock.contains("other:item/other_item"),
                "the kept copy's items reach the Bedrock input: " + bedrock);
    }

    private static MixOutput mix(Path root, List<File> packs, Set<File> excluded) throws Exception {
        File output = root.resolve("output").toFile();
        return new MixEngine(SILENT, () -> false).run(new MixInput(packs, output, output,
                root.resolve("logs").toFile(), "mixed", false, null, excluded));
    }

    private static String paper(File mergedDir) throws Exception {
        return Files.readString(new File(mergedDir, "assets/minecraft/items/paper.json").toPath());
    }

    /** A pack whose only item is a custom_model_data entry in paper.json pointing at its own model. */
    private static File pack(Path root, String name, int threshold, String model) throws Exception {
        Path file = root.resolve(name + ".zip");
        String namespace = model.substring(0, model.indexOf(':'));
        String path = model.substring(model.indexOf(':') + 1);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            entry(zip, "pack.mcmeta", "{\"pack\":{\"pack_format\":46,\"description\":\"" + name + "\"}}");
            entry(zip, "assets/minecraft/items/paper.json", "{\"model\":{\"type\":\"minecraft:range_dispatch\","
                    + "\"property\":\"minecraft:custom_model_data\",\"index\":0,"
                    + "\"fallback\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/paper\"},"
                    + "\"entries\":[{\"threshold\":" + threshold + ",\"model\":{\"type\":\"minecraft:model\",\"model\":\""
                    + model + "\"}}]}}");
            entry(zip, "assets/" + namespace + "/models/" + path + ".json",
                    "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"" + model + "\"}}");
        }
        return file.toFile();
    }

    private static void entry(ZipOutputStream zip, String name, String value) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
