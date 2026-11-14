package com.magmaguy.resourcepackmanager.bedrock.items;

import dev.lone.itemsadder.api.CustomStack;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.th0rgal.oraxen.api.OraxenItems;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.core.util.Key;
import com.nexomc.nexo.api.NexoItems;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Each custom-item plugin is reached by reflection through its own class loader. Stand-ins
 * under the plugins' real package names give the same API shapes; the test class loader plays
 * the plugin's.
 */
final class CustomItemSourceTest {

    private final ClassLoader plugin = getClass().getClassLoader();

    @AfterEach
    void reset() {
        CraftEngineItems.ITEMS.clear();
        CraftEngineItems.notReady = false;
        BukkitItemManager.CLIENT_BOUND.clear();
        BukkitItemManager.throwWithoutViewer = false;
        NexoItems.ITEMS.clear();
        OraxenItems.ITEMS.clear();
        CustomStack.ITEMS.clear();
        MythicBukkit.ITEMS.clear();
    }

    @Test
    void craftEngineItemsBuildAsTheClientReceivesThem() throws Exception {
        TestStack server = new TestStack("server");
        TestStack client = new TestStack("client");
        TestStack plain = new TestStack("plain");
        BukkitItemManager.CLIENT_BOUND.put(server, client);
        CraftEngineItems.ITEMS.put(Key.of("default:topaz"), new BukkitItemDefinition("default:topaz", false, () -> server));
        CraftEngineItems.ITEMS.put(Key.of("default:ruby"), new BukkitItemDefinition("default:ruby", false, () -> plain));
        CraftEngineItems.ITEMS.put(Key.of("minecraft:stick"), new BukkitItemDefinition("minecraft:stick", true, () -> plain));

        Map<String, Object> built = build(new CraftEngineItemSource().list(plugin));

        assertEquals(List.of("default:topaz", "default:ruby"), List.copyOf(built.keySet()));
        assertSame(client, built.get("default:topaz"));
        // An empty conversion result means the client gets the server stack unchanged.
        assertSame(plain, built.get("default:ruby"));
    }

    @Test
    void craftEngineFallsBackToTheServerStackWhenTheConversionNeedsAViewer() throws Exception {
        TestStack server = new TestStack("server");
        BukkitItemManager.CLIENT_BOUND.put(server, new TestStack("client"));
        BukkitItemManager.throwWithoutViewer = true;
        CraftEngineItems.ITEMS.put(Key.of("default:topaz"), new BukkitItemDefinition("default:topaz", false, () -> server));

        assertSame(server, build(new CraftEngineItemSource().list(plugin)).get("default:topaz"));
    }

    @Test
    void craftEngineBeforeItsItemsLoadListsNothing() throws Exception {
        CraftEngineItems.ITEMS.put(Key.of("default:topaz"), new BukkitItemDefinition("default:topaz", false, () -> null));
        CraftEngineItems.notReady = true;

        assertEquals(List.of(), new CraftEngineItemSource().list(plugin));
    }

    @Test
    void nexoItemsComeFromItsKotlinObject() throws Exception {
        TestStack hat = new TestStack("hat");
        NexoItems.ITEMS.put("forest_hat", () -> hat);

        Map<String, Object> built = build(new NexoItemSource().list(plugin));

        assertSame(hat, built.get("forest_hat"));
    }

    @Test
    void oraxenItemsComeFromItsStaticApi() throws Exception {
        TestStack tomato = new TestStack("tomato");
        OraxenItems.ITEMS.put("tomato", () -> tomato);

        assertSame(tomato, build(new OraxenItemSource().list(plugin)).get("tomato"));
    }

    @Test
    void itemsAdderItemsComeFromCustomStack() throws Exception {
        TestStack ruby = new TestStack("ruby");
        CustomStack.ITEMS.put("iasurvival:ruby", () -> ruby);

        assertSame(ruby, build(new ItemsAdderItemSource().list(plugin)).get("iasurvival:ruby"));
    }

    @Test
    void mythicItemsAreAdaptedToBukkitStacks() throws Exception {
        TestStack blade = new TestStack("blade");
        MythicBukkit.ITEMS.put("CrucibleBlade", () -> blade);

        assertSame(blade, build(new MythicItemSource().list(plugin)).get("CrucibleBlade"));
    }

    @Test
    void aBrokenItemFailsOnlyItsOwnBuild() throws Exception {
        TestStack good = new TestStack("good");
        NexoItems.ITEMS.put("broken", () -> {
            throw new IllegalStateException("bad config");
        });
        NexoItems.ITEMS.put("good", () -> good);

        List<CustomItemSource.Item> items = new NexoItemSource().list(plugin);

        assertEquals(2, items.size());
        assertThrows(ApiReflection.PluginApiException.class, () -> items.get(0).build().call());
        assertSame(good, items.get(1).build().call());
    }

    @Test
    void anItemRemovedAfterListingBuildsNothing() throws Exception {
        OraxenItems.ITEMS.put("gone", () -> new TestStack("gone"));
        List<CustomItemSource.Item> items = new OraxenItemSource().list(plugin);
        OraxenItems.ITEMS.clear();

        assertNull(items.get(0).build().call());
    }

    @Test
    void aMissingPluginApiIsReportedToTheCaller() {
        ClassLoader withoutPlugins = new ClassLoader(null) { };

        for (CustomItemSource source : ItemComponentSnapshots.SOURCES) {
            assertThrows(ClassNotFoundException.class, () -> source.list(withoutPlugins), source.pluginName());
        }
    }

    private static Map<String, Object> build(List<CustomItemSource.Item> items) throws Exception {
        Map<String, Object> built = new LinkedHashMap<>();
        for (CustomItemSource.Item item : items) built.put(item.id(), item.build().call());
        return built;
    }
}
