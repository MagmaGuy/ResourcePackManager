package io.lumine.mythic.bukkit;

import io.lumine.mythic.api.adapters.AbstractItemStack;
import org.bukkit.inventory.ItemStack;

/** Test stand-in for Mythic's BukkitAdapter. Not Mythic code. */
public final class BukkitAdapter {
    public static ItemStack adapt(AbstractItemStack stack) {
        return ((MythicBukkit.Wrapped) stack).stack();
    }

    public static AbstractItemStack adapt(ItemStack stack) {
        return new MythicBukkit.Wrapped(stack);
    }
}
