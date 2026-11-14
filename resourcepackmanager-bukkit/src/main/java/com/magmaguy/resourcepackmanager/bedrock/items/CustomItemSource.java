package com.magmaguy.resourcepackmanager.bedrock.items;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * One custom-item plugin RSPM can ask for every item it registers, so the Bedrock conversion
 * can read what each item changes against its base item.
 *
 * <p>RSPM does not compile against these plugins. Each source reaches the plugin's public API
 * by reflection through the plugin's own class loader, so it only runs on servers that have the
 * plugin, and an API change costs that plugin's items their components instead of breaking the
 * conversion.</p>
 */
interface CustomItemSource {

    /** The plugin name as Bukkit knows it, such as {@code CraftEngine}. */
    String pluginName();

    /**
     * Lists the plugin's custom items. Listing is cheap; each item's stack is built later, a few
     * per tick, through its {@link Item#build()}.
     *
     * @param pluginClasses the plugin's class loader
     * @return every registered custom item; empty while the plugin has not loaded its items
     * @throws ReflectiveOperationException when the API this source relies on is missing
     */
    List<Item> list(ClassLoader pluginClasses) throws ReflectiveOperationException;

    /**
     * @param id    the plugin's id for the item
     * @param build builds a fresh {@code org.bukkit.inventory.ItemStack} of the item, as the
     *              client receives it; called on the server thread
     */
    record Item(String id, Callable<Object> build) {
    }
}
