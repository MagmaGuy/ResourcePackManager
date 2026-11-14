package io.th0rgal.oraxen.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Test stand-in for Oraxen's static OraxenItems. Not Oraxen code. */
public final class OraxenItems {
    public static final Map<String, Supplier<Object>> ITEMS = new LinkedHashMap<>();

    public static String[] getItemNames() {
        return ITEMS.keySet().toArray(new String[0]);
    }

    public static Builder getItemById(String id) {
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
