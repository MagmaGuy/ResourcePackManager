package com.magmaguy.resourcepackmanager.bedrock.items;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * MythicMobs items, which include MythicCrucible's: {@code MythicBukkit.inst().getItemManager()}
 * lists them, {@code MythicItem.generateItemStack(1)} builds one and
 * {@code BukkitAdapter.adapt} turns it into a Bukkit stack.
 */
final class MythicItemSource implements CustomItemSource {

    static final String MYTHIC = "io.lumine.mythic.bukkit.MythicBukkit";
    static final String ADAPTER = "io.lumine.mythic.bukkit.BukkitAdapter";

    @Override
    public String pluginName() {
        return "MythicMobs";
    }

    @Override
    public List<Item> list(ClassLoader pluginClasses) throws ReflectiveOperationException {
        Class<?> mythic = Class.forName(MYTHIC, true, pluginClasses);
        Object plugin = ApiReflection.invoke(mythic.getMethod("inst"), null);
        if (plugin == null) return List.of();
        Object manager = ApiReflection.call(plugin, "getItemManager");
        Method adapt = adapt(Class.forName(ADAPTER, true, pluginClasses));

        List<Item> out = new ArrayList<>();
        for (String name : names(manager)) {
            out.add(new Item(name, () -> {
                Method getItem = manager.getClass().getMethod("getItem", String.class);
                Object item = ApiReflection.unwrap(ApiReflection.invoke(getItem, manager, name));
                if (item == null) return null;
                Method generate = item.getClass().getMethod("generateItemStack", int.class);
                Object abstractStack = ApiReflection.invoke(generate, item, 1);
                return abstractStack == null ? null : ApiReflection.invoke(adapt, null, abstractStack);
            }));
        }
        return out;
    }

    private static List<String> names(Object manager) throws ReflectiveOperationException {
        try {
            return ApiReflection.strings(ApiReflection.call(manager, "getItemNames"));
        } catch (NoSuchMethodException olderApi) {
            List<String> names = new ArrayList<>();
            Object items = ApiReflection.call(manager, "getItems");
            if (items instanceof Iterable<?> iterable) {
                for (Object item : iterable) {
                    Object name = ApiReflection.call(item, "getInternalName");
                    if (name != null) names.add(name.toString());
                }
            }
            return names;
        }
    }

    /** {@code BukkitAdapter.adapt(AbstractItemStack)}, the overload that returns a Bukkit stack. */
    private static Method adapt(Class<?> adapter) throws NoSuchMethodException {
        for (Method method : adapter.getMethods()) {
            if (!method.getName().equals("adapt") || method.getParameterCount() != 1) continue;
            if (!method.getReturnType().getName().equals("org.bukkit.inventory.ItemStack")) continue;
            if (method.getParameterTypes()[0].getSimpleName().equals("AbstractItemStack")) return method;
        }
        throw new NoSuchMethodException(adapter.getName() + ".adapt(AbstractItemStack)");
    }
}
