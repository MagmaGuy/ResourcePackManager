package net.momirealms.craftengine.bukkit.item;

import net.momirealms.craftengine.core.item.ItemDefinition;
import net.momirealms.craftengine.core.util.Key;

import java.util.function.Supplier;

/** Test stand-in for CraftEngine's BukkitItemDefinition#buildBukkitItem(). Not CraftEngine code. */
public final class BukkitItemDefinition implements ItemDefinition {
    private final Key id;
    private final boolean vanilla;
    private final Supplier<Object> build;

    public BukkitItemDefinition(String id, boolean vanilla, Supplier<Object> build) {
        this.id = Key.of(id);
        this.vanilla = vanilla;
        this.build = build;
    }

    @Override public boolean isVanillaItem() { return vanilla; }
    @Override public Key id() { return id; }
    @Override public Key material() { return Key.of("minecraft:nether_brick"); }
    @Override public Key clientBoundMaterial() { return Key.of("minecraft:nether_brick"); }

    public Object buildBukkitItem() {
        return build.get();
    }
}
