package net.momirealms.craftengine.bukkit.api;

import net.momirealms.craftengine.core.item.ItemDefinition;
import net.momirealms.craftengine.core.util.Key;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Test stand-in for CraftEngine's {@code CraftEngineItems.loadedItems()}. Tests fill
 * {@link #ITEMS}, or set {@link #notReady} to mimic CraftEngine before its item manager
 * exists. Not CraftEngine code.
 */
public final class CraftEngineItems {
    public static final Map<Key, ItemDefinition> ITEMS = new LinkedHashMap<>();
    public static boolean notReady;

    private CraftEngineItems() {
    }

    public static Map<Key, ItemDefinition> loadedItems() {
        if (notReady) throw new IllegalStateException("BukkitItemManager is not available");
        return Collections.unmodifiableMap(ITEMS);
    }
}
