package com.magmaguy.resourcepackmanager.bedrock;

import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.core.item.ItemDefinition;
import net.momirealms.craftengine.core.util.Key;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CraftEngine items sit on nether_brick unless they set a material, and nothing in the pack
 * says so, so Bedrock players saw the plain base item (RSPM#20). The bases come from
 * CraftEngine's API, reached by reflection; test stand-ins under net.momirealms.craftengine
 * provide the same API shape.
 */
final class CraftEngineItemBasesTest {

    @TempDir
    Path temp;

    @AfterEach
    void resetApi() {
        CraftEngineItems.ITEMS.clear();
        CraftEngineItems.notReady = false;
    }

    @Test
    void customItemsDeclareTheMaterialTheClientReceives() throws ReflectiveOperationException {
        item("default:topaz", "minecraft:nether_brick", "minecraft:nether_brick", false);
        item("default:ruby_sword", "minecraft:diamond_sword", "minecraft:diamond_sword", false);
        item("default:chair", "minecraft:nether_brick", "minecraft:paper", false);
        item("minecraft:diamond_sword", "minecraft:diamond_sword", "minecraft:diamond_sword", true);
        item("default:Bad Id", "minecraft:nether_brick", "minecraft:nether_brick", false);

        Map<String, List<String>> declared = CraftEngineItemBases.read(getClass().getClassLoader());

        assertEquals(new TreeMap<>(Map.of(
                "default:topaz", List.of("minecraft:nether_brick"),
                "default:ruby_sword", List.of("minecraft:diamond_sword"),
                "default:chair", List.of("minecraft:paper", "minecraft:nether_brick"))), new TreeMap<>(declared));
    }

    @Test
    void beforeCraftEngineLoadsItsItemsNothingIsDeclared() throws ReflectiveOperationException {
        item("default:topaz", "minecraft:nether_brick", "minecraft:nether_brick", false);
        CraftEngineItems.notReady = true;

        assertEquals(Map.of(), CraftEngineItemBases.read(getClass().getClassLoader()));
    }

    @Test
    void aMissingApiIsReportedToTheCaller() {
        ClassLoader withoutCraftEngine = new ClassLoader(null) { };

        assertThrows(ClassNotFoundException.class, () -> CraftEngineItemBases.read(withoutCraftEngine));
    }

    @Test
    void itemsMapUnderTheirDeclaredBaseAndKeepEveryGuessedBase() throws Exception {
        item("default:topaz", "minecraft:nether_brick", "minecraft:nether_brick", false);
        item("default:palm_leaf", "minecraft:nether_brick", "minecraft:paper", false);

        Map<String, Set<String>> bases = convert(CraftEngineItemBases.read(getClass().getClassLoader()));

        assertTrue(bases.get("default:topaz").contains("minecraft:nether_brick"), "bases: " + bases.get("default:topaz"));
        assertTrue(bases.get("default:topaz").containsAll(BedrockMappingFixture.GUESSED_BASES), "bases: " + bases.get("default:topaz"));
        assertTrue(bases.get("default:palm_leaf").containsAll(List.of("minecraft:paper", "minecraft:nether_brick")),
                "bases: " + bases.get("default:palm_leaf"));
    }

    @Test
    void withoutDeclarationsItemsHaveNoNetherBrickMapping() throws IOException {
        Map<String, Set<String>> bases = convert(Map.of());

        assertFalse(bases.get("default:topaz").contains("minecraft:nether_brick"), "bases: " + bases.get("default:topaz"));
        assertEquals(new TreeSet<>(BedrockMappingFixture.GUESSED_BASES), bases.get("default:topaz"));
    }

    private Map<String, Set<String>> convert(Map<String, List<String>> declared) throws IOException {
        return BedrockMappingFixture.convert(temp, "default", List.of("topaz", "palm_leaf"), declared);
    }

    private static void item(String id, String material, String clientBoundMaterial, boolean vanilla) {
        Key key = Key.of(id);
        CraftEngineItems.ITEMS.put(key, new Definition(vanilla, key, Key.of(material), Key.of(clientBoundMaterial)));
    }

    private record Definition(boolean isVanillaItem, Key id, Key material, Key clientBoundMaterial)
            implements ItemDefinition {
    }
}
