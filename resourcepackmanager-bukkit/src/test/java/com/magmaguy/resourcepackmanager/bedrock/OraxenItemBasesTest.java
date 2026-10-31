package com.magmaguy.resourcepackmanager.bedrock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Oraxen's item definitions carry no material, so without the material from its item
 * configs the converter maps every Oraxen item under its paper/stick/sword guesses.
 * Oraxen + CustomCrops crops on APPLE then showed as plain apples on Bedrock
 * (SUPERSHOOT, #patron-support, 2026-10). Real config files and a real pack go through
 * {@link OraxenItemBases} and {@link BedrockConversion}.
 */
final class OraxenItemBasesTest {

    private static final List<String> GUESSED_BASES = BedrockMappingFixture.GUESSED_BASES;

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
        BedrockMappingFixture.write(oraxen.resolve("items/farming/crops.yml"), """
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
        BedrockMappingFixture.write(oraxen.resolve("items/extra.yml"), """
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
        BedrockMappingFixture.write(oraxen.resolve("items/broken.yml"), "tomato: [unterminated\n");
        return oraxen.toFile();
    }

    private Map<String, Set<String>> convert(Map<String, List<String>> declared) throws IOException {
        return BedrockMappingFixture.convert(temp, "oraxen",
                List.of("tomato", "eggplant", "seed_bag", "watering_can"), declared);
    }
}
