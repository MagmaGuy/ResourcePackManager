package net.momirealms.craftengine.bukkit.item;

import org.bukkit.inventory.ItemStack;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Test stand-in for CraftEngine's BukkitItemManager.s2c(ItemStack, Player): returns the
 * client-bound stack registered for a server stack, or empty when the client gets it
 * unchanged. Not CraftEngine code.
 */
public final class BukkitItemManager {
    public static final Map<ItemStack, ItemStack> CLIENT_BOUND = new IdentityHashMap<>();
    public static boolean throwWithoutViewer;
    private static final BukkitItemManager INSTANCE = new BukkitItemManager();

    public static BukkitItemManager instance() {
        return INSTANCE;
    }

    /** The overload for CraftEngine's own wrapped item, which RSPM must not pick for a Bukkit stack. */
    public Optional<Object> s2c(StringBuilder wrappedItem, Object player) {
        throw new AssertionError("wrong overload");
    }

    public Optional<ItemStack> s2c(ItemStack item, Object player) {
        if (throwWithoutViewer && player == null) throw new NullPointerException("player");
        return Optional.ofNullable(CLIENT_BOUND.get(item));
    }
}
