package com.magmaguy.resourcepackmanager.bedrock.items;

import java.util.ArrayList;
import java.util.List;

/** Oraxen items, from {@code OraxenItems.getItemNames()} and {@code getItemById(id).build()}. */
final class OraxenItemSource implements CustomItemSource {

    static final String ITEMS_API = "io.th0rgal.oraxen.api.OraxenItems";

    @Override
    public String pluginName() {
        return "Oraxen";
    }

    @Override
    public List<Item> list(ClassLoader pluginClasses) throws ReflectiveOperationException {
        Class<?> api = Class.forName(ITEMS_API, true, pluginClasses);
        List<Item> out = new ArrayList<>();
        for (String id : ApiReflection.strings(ApiReflection.callApi(api, "getItemNames", new Class<?>[0]))) {
            out.add(new Item(id, () -> {
                Object builder = ApiReflection.unwrap(
                        ApiReflection.callApi(api, "getItemById", new Class<?>[]{String.class}, id));
                return builder == null ? null : ApiReflection.call(builder, "build");
            }));
        }
        return out;
    }
}
