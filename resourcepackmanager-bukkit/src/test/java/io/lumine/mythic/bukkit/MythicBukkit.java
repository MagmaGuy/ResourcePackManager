package io.lumine.mythic.bukkit;

import io.lumine.mythic.api.adapters.AbstractItemStack;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** Test stand-in for Mythic's MythicBukkit.inst().getItemManager(). Not Mythic code. */
public final class MythicBukkit {
    public static final Map<String, Supplier<ItemStack>> ITEMS = new LinkedHashMap<>();
    private static final MythicBukkit INSTANCE = new MythicBukkit();

    public static MythicBukkit inst() {
        return INSTANCE;
    }

    public ItemManager getItemManager() {
        return new ItemManager();
    }

    public static final class ItemManager {
        public Collection<String> getItemNames() {
            return ITEMS.keySet();
        }

        public Optional<MythicItem> getItem(String name) {
            Supplier<ItemStack> build = ITEMS.get(name);
            return build == null ? Optional.empty() : Optional.of(new MythicItem(build));
        }
    }

    public static final class MythicItem {
        private final Supplier<ItemStack> build;

        MythicItem(Supplier<ItemStack> build) {
            this.build = build;
        }

        public AbstractItemStack generateItemStack(int amount) {
            return new Wrapped(build.get());
        }
    }

    public record Wrapped(ItemStack stack) implements AbstractItemStack {
    }
}
