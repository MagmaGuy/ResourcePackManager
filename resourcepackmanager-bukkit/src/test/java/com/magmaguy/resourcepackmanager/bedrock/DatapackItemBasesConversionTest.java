package com.magmaguy.resourcepackmanager.bedrock;

import com.magmaguy.resourcepackmanager.bedrock.generic.DatapackItemBases;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A data pack that makes its own tools, shaped like the one in support ticket "Converting
 * issue" (2026-11-18): the resource pack holds {@code kit:<tool>} item models, and only the
 * data pack's recipes say which vanilla item each one sits on. Three of the tools change their
 * look with wear through a condition Geyser cannot test. On Bedrock all four stayed vanilla.
 */
final class DatapackItemBasesConversionTest {

    private static final List<String> TOOLS = List.of("copper_mattock", "copper_shears", "tinder", "copper_dolabra");
    private static final Map<String, String> BASE_OF = Map.of(
            "copper_mattock", "minecraft:copper_hoe",
            "copper_shears", "minecraft:shears",
            "tinder", "minecraft:flint_and_steel",
            "copper_dolabra", "minecraft:copper_axe");

    @TempDir
    Path temp;

    @Test
    void eachToolMapsUnderTheBaseItemItsRecipeMakesItOn() throws IOException {
        Map<String, List<String>> declared = DatapackItemBases.read(List.of(writeDatapack()), () -> false).basesByModel();

        Map<String, Set<String>> bases = convert(declared);

        for (String tool : TOOLS)
            assertEquals(Set.of(BASE_OF.get(tool)), bases.get("kit:" + tool), tool + " bases");
    }

    @Test
    void withoutTheDataPackNoToolReachesItsRealBaseItem() throws IOException {
        Map<String, Set<String>> bases = convert(Map.of());

        for (String tool : TOOLS)
            assertEquals(new TreeSet<>(BedrockMappingFixture.GUESSED_BASES), bases.get("kit:" + tool), tool + " bases");
    }

    private Map<String, Set<String>> convert(Map<String, List<String>> declared) throws IOException {
        Map<String, String> definitions = new LinkedHashMap<>();
        for (String tool : TOOLS)
            if (!tool.equals("tinder")) definitions.put(tool, wearingDefinition(tool));
        return BedrockMappingFixture.bases(BedrockMappingFixture.mappings(
                temp, "kit", TOOLS, declared, List.of(), definitions));
    }

    /** Picks the model by the stack's damage, with the fresh look as the final else-branch. */
    private static String wearingDefinition(String tool) {
        String fresh = "{\"type\":\"minecraft:model\",\"model\":\"kit:item/" + tool + "\"}";
        return "{\"model\":{\"type\":\"minecraft:condition\",\"property\":\"minecraft:component\","
                + "\"predicate\":\"minecraft:damage\",\"value\":{\"damage\":{\"min\":0,\"max\":87}},"
                + "\"on_true\":" + fresh + ","
                + "\"on_false\":{\"type\":\"minecraft:condition\",\"property\":\"minecraft:component\","
                + "\"predicate\":\"minecraft:damage\",\"value\":{\"damage\":{\"min\":88,\"max\":999}},"
                + "\"on_true\":{\"type\":\"minecraft:model\",\"model\":\"kit:item/" + tool + "_oxidized\"},"
                + "\"on_false\":" + fresh + "}}}";
    }

    private File writeDatapack() throws IOException {
        Path datapack = Files.createTempDirectory(temp, "datapack-");
        BedrockMappingFixture.write(datapack.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":101,\"description\":\"kit\"}}");
        for (String tool : TOOLS)
            BedrockMappingFixture.write(datapack.resolve("data/kit/recipe/crafting/" + tool + ".json"),
                    "{\"type\":\"minecraft:crafting_shapeless\",\"category\":\"equipment\","
                            + "\"ingredients\":[\"minecraft:stick\",\"minecraft:copper_ingot\"],"
                            + "\"result\":{\"id\":\"" + BASE_OF.get(tool) + "\",\"components\":{"
                            + "\"minecraft:item_model\":\"kit:" + tool + "\","
                            + "\"minecraft:item_name\":{\"translate\":\"item.kit." + tool + "\"},"
                            + "\"minecraft:custom_data\":{\"version\":1}}}}");
        return datapack.toFile();
    }
}
