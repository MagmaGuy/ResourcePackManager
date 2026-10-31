package com.magmaguy.resourcepackmanager.bedrock;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads the Java base item of every CraftEngine item from CraftEngine's public API, for
 * {@link com.magmaguy.resourcepackmanager.bedrock.BedrockConverterContext#declaredBaseItems()}.
 *
 * <p>CraftEngine puts its items on {@code nether_brick} unless an item sets {@code material}, and
 * its pack says nothing about that choice, so the converter fell back to its paper, stick and sword
 * guesses and Bedrock players saw the plain base item. CraftEngine's item_model defaults to the
 * item id, so each item maps under its id.</p>
 *
 * <p>RSPM does not compile against CraftEngine. It calls {@code CraftEngineItems.loadedItems()}
 * through CraftEngine's own class loader, so this only runs on servers that have CraftEngine, and
 * an API change costs these items their declared base instead of breaking the conversion. Items
 * with a custom {@code item_model} keep the guessed bases: the API does not expose that model.</p>
 */
final class CraftEngineItemBases {

    static final String ITEMS_API = "net.momirealms.craftengine.bukkit.api.CraftEngineItems";
    static final String ITEM_DEFINITION = "net.momirealms.craftengine.core.item.ItemDefinition";
    private static final Pattern ITEM_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern VANILLA_ITEM = Pattern.compile("minecraft:[a-z0-9_]+");

    private CraftEngineItemBases() {
    }

    /**
     * @param craftEngineClasses CraftEngine's plugin class loader
     * @return base items keyed by item model id, such as
     *         {@code default:topaz -> [minecraft:nether_brick]}; empty while CraftEngine has not
     *         loaded its items
     * @throws ReflectiveOperationException when the API RSPM relies on is missing
     */
    static Map<String, List<String>> read(ClassLoader craftEngineClasses) throws ReflectiveOperationException {
        Class<?> api = Class.forName(ITEMS_API, true, craftEngineClasses);
        Class<?> definitionType = Class.forName(ITEM_DEFINITION, false, craftEngineClasses);
        Method loadedItems = api.getMethod("loadedItems");
        Method isVanillaItem = definitionType.getMethod("isVanillaItem");
        Method id = definitionType.getMethod("id");
        Method clientBoundMaterial = definitionType.getMethod("clientBoundMaterial");
        Method material = definitionType.getMethod("material");

        List<Object> definitions;
        try {
            Object loaded = loadedItems.invoke(null);
            if (!(loaded instanceof Map<?, ?> items)) return Map.of();
            definitions = new ArrayList<>(items.values());
        } catch (InvocationTargetException notReady) {
            // CraftEngine throws until its item manager exists; no items are declared yet.
            return Map.of();
        }

        Map<String, List<String>> bases = new LinkedHashMap<>();
        for (Object definition : definitions) {
            if (!definitionType.isInstance(definition)) continue;
            try {
                if (Boolean.TRUE.equals(isVanillaItem.invoke(definition))) continue;
                String itemId = String.valueOf(id.invoke(definition)).toLowerCase(Locale.ROOT);
                if (!ITEM_ID.matcher(itemId).matches()) continue;
                // The client receives clientBoundMaterial, and Geyser translates what the client
                // receives; material is the server-side item when the two differ.
                List<String> declared = new ArrayList<>(2);
                addVanillaItem(declared, clientBoundMaterial.invoke(definition));
                addVanillaItem(declared, material.invoke(definition));
                if (!declared.isEmpty()) bases.put(itemId, List.copyOf(declared));
            } catch (InvocationTargetException unreadableItem) {
                // One broken definition only loses its own declaration.
            }
        }
        return bases;
    }

    private static void addVanillaItem(List<String> declared, Object key) {
        if (key == null) return;
        String item = key.toString().toLowerCase(Locale.ROOT);
        if (!item.contains(":")) item = "minecraft:" + item;
        if (VANILLA_ITEM.matcher(item).matches() && !declared.contains(item)) declared.add(item);
    }
}
