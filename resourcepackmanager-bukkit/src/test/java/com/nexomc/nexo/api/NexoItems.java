package com.nexomc.nexo.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Test stand-in for Nexo's Kotlin object NexoItems, with its methods on INSTANCE. Not Nexo code. */
public final class NexoItems {
    public static final NexoItems INSTANCE = new NexoItems();
    public static final Map<String, Supplier<Object>> ITEMS = new LinkedHashMap<>();

    public Set<String> itemNames() {
        return ITEMS.keySet();
    }

    public Builder itemFromId(String id) {
        Supplier<Object> build = ITEMS.get(id);
        return build == null ? null : new Builder(build);
    }

    public static final class Builder {
        private final Supplier<Object> build;

        Builder(Supplier<Object> build) {
            this.build = build;
        }

        public Object build() {
            return build.get();
        }
    }
}
