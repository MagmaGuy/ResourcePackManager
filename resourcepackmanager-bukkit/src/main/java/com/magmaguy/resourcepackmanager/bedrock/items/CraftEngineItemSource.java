package com.magmaguy.resourcepackmanager.bedrock.items;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CraftEngine items, from {@code CraftEngineItems.loadedItems()}. Each stack is built with
 * {@code buildBukkitItem()} and then passed through CraftEngine's own server-to-client
 * conversion ({@code BukkitItemManager.s2c}), because a CraftEngine item can send the client a
 * different material and components than it keeps on the server, and Geyser translates what
 * the client receives. When that conversion is unavailable the server stack is used.
 */
final class CraftEngineItemSource implements CustomItemSource {

    static final String ITEMS_API = "net.momirealms.craftengine.bukkit.api.CraftEngineItems";
    static final String ITEM_DEFINITION = "net.momirealms.craftengine.core.item.ItemDefinition";
    static final String ITEM_MANAGER = "net.momirealms.craftengine.bukkit.item.BukkitItemManager";

    @Override
    public String pluginName() {
        return "CraftEngine";
    }

    @Override
    public List<Item> list(ClassLoader pluginClasses) throws ReflectiveOperationException {
        Class<?> api = Class.forName(ITEMS_API, true, pluginClasses);
        Class<?> definitionType = Class.forName(ITEM_DEFINITION, false, pluginClasses);
        Method isVanillaItem = definitionType.getMethod("isVanillaItem");
        Method id = definitionType.getMethod("id");
        Object loaded;
        try {
            loaded = api.getMethod("loadedItems").invoke(null);
        } catch (InvocationTargetException notReady) {
            // CraftEngine throws until its item manager exists; it has no items yet.
            return List.of();
        }
        if (!(loaded instanceof Map<?, ?> items)) return List.of();

        ClientBound clientBound = ClientBound.find(pluginClasses);
        List<Item> out = new ArrayList<>();
        for (Object definition : items.values()) {
            if (!definitionType.isInstance(definition)) continue;
            try {
                if (Boolean.TRUE.equals(isVanillaItem.invoke(definition))) continue;
                String itemId = String.valueOf(id.invoke(definition));
                out.add(new Item(itemId, () -> clientBound.apply(ApiReflection.call(definition, "buildBukkitItem"))));
            } catch (InvocationTargetException unreadable) {
                // One broken definition only loses itself.
            }
        }
        return out;
    }

    /** CraftEngine's server-to-client item conversion, or the identity when it is missing. */
    private record ClientBound(Object manager, List<Method> s2c) {

        static ClientBound find(ClassLoader pluginClasses) {
            try {
                Class<?> managerType = Class.forName(ITEM_MANAGER, true, pluginClasses);
                Object manager = managerType.getMethod("instance").invoke(null);
                if (manager == null) return new ClientBound(null, List.of());
                List<Method> conversions = new ArrayList<>();
                for (Method method : managerType.getMethods()) {
                    if (method.getName().equals("s2c") && method.getParameterCount() == 2) conversions.add(method);
                }
                return new ClientBound(manager, conversions);
            } catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) {
                // Older or newer CraftEngine without this entry point: use server stacks.
                return new ClientBound(null, List.of());
            }
        }

        /**
         * Runs the overload that takes a Bukkit stack, {@code s2c(ItemStack, Player)}, with no
         * viewer. It returns an empty optional when the client receives the stack unchanged.
         */
        Object apply(Object serverStack) {
            if (manager == null || serverStack == null) return serverStack;
            for (Method conversion : s2c) {
                if (!conversion.getParameterTypes()[0].isInstance(serverStack)) continue;
                try {
                    Object clientStack = ApiReflection.unwrap(conversion.invoke(manager, serverStack, null));
                    return clientStack == null ? serverStack : clientStack;
                } catch (ReflectiveOperationException | RuntimeException noViewer) {
                    // The conversion may need a viewing player; the server stack is the next best.
                    return serverStack;
                }
            }
            return serverStack;
        }
    }
}
