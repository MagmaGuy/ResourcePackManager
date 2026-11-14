package com.magmaguy.resourcepackmanager.bedrock.items;

import java.util.ArrayList;
import java.util.List;

/**
 * Nexo items, from {@code NexoItems.itemNames()} and {@code NexoItems.itemFromId(id).build()}.
 * {@code NexoItems} is a Kotlin object, so its methods may be static or live on
 * {@code INSTANCE}; both are handled.
 */
final class NexoItemSource implements CustomItemSource {

    static final String ITEMS_API = "com.nexomc.nexo.api.NexoItems";

    @Override
    public String pluginName() {
        return "Nexo";
    }

    @Override
    public List<Item> list(ClassLoader pluginClasses) throws ReflectiveOperationException {
        Class<?> api = Class.forName(ITEMS_API, true, pluginClasses);
        List<Item> out = new ArrayList<>();
        for (String id : ApiReflection.strings(ApiReflection.callApi(api, "itemNames", new Class<?>[0]))) {
            out.add(new Item(id, () -> {
                Object builder = ApiReflection.callApi(api, "itemFromId", new Class<?>[]{String.class}, id);
                return builder == null ? null : ApiReflection.call(builder, "build");
            }));
        }
        return out;
    }
}
