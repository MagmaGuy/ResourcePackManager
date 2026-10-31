package net.momirealms.craftengine.core.util;

/**
 * Test stand-in for CraftEngine's {@code Key}: only what CraftEngineItemBases relies on,
 * a {@code namespace:value} string form. Not CraftEngine code.
 */
public record Key(String namespace, String value) {
    public static Key of(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? new Key("minecraft", id) : new Key(id.substring(0, colon), id.substring(colon + 1));
    }

    @Override
    public String toString() {
        return namespace + ":" + value;
    }
}
