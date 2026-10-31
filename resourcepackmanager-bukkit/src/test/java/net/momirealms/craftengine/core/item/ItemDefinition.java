package net.momirealms.craftengine.core.item;

import net.momirealms.craftengine.core.util.Key;

/**
 * Test stand-in for the accessors of CraftEngine's {@code ItemDefinition} that
 * CraftEngineItemBases reads. Not CraftEngine code.
 */
public interface ItemDefinition {
    boolean isVanillaItem();

    Key id();

    Key material();

    Key clientBoundMaterial();
}
