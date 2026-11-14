package dev.lone.itemsadder.api;

import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Test stand-in for ItemsAdder's CustomStack. Not ItemsAdder code. */
public class CustomStack {
    public static final Map<String, Supplier<ItemStack>> ITEMS = new LinkedHashMap<>();
    private final Supplier<ItemStack> build;

    private CustomStack(Supplier<ItemStack> build) {
        this.build = build;
    }

    public static Set<String> getNamespacedIdsInRegistry() {
        return ITEMS.keySet();
    }

    public static CustomStack getInstance(String id) {
        Supplier<ItemStack> build = ITEMS.get(id);
        return build == null ? null : new CustomStack(build);
    }

    public ItemStack getItemStack() {
        return build.get();
    }
}
