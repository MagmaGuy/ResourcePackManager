package com.magmaguy.resourcepackmanager.bedrock.items;

import org.bukkit.inventory.ItemStack;

/** A Bukkit stack that needs no server, told apart by its label. Test code. */
public final class TestStack extends ItemStack {
    public final String label;

    public TestStack(String label) {
        this.label = label;
    }

    @Override
    public boolean equals(Object other) {
        return this == other;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    @Override
    public String toString() {
        return "TestStack[" + label + "]";
    }
}
