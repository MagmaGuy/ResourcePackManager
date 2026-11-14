package com.magmaguy.resourcepackmanager.bedrock.items;

import java.util.ArrayList;
import java.util.List;

/**
 * ItemsAdder items, from {@code CustomStack.getNamespacedIdsInRegistry()} and
 * {@code CustomStack.getInstance(id).getItemStack()}.
 */
final class ItemsAdderItemSource implements CustomItemSource {

    static final String CUSTOM_STACK = "dev.lone.itemsadder.api.CustomStack";

    @Override
    public String pluginName() {
        return "ItemsAdder";
    }

    @Override
    public List<Item> list(ClassLoader pluginClasses) throws ReflectiveOperationException {
        Class<?> api = Class.forName(CUSTOM_STACK, true, pluginClasses);
        List<Item> out = new ArrayList<>();
        for (String id : ApiReflection.strings(
                ApiReflection.callApi(api, "getNamespacedIdsInRegistry", new Class<?>[0]))) {
            out.add(new Item(id, () -> {
                Object customStack = ApiReflection.callApi(api, "getInstance", new Class<?>[]{String.class}, id);
                return customStack == null ? null : ApiReflection.call(customStack, "getItemStack");
            }));
        }
        return out;
    }
}
