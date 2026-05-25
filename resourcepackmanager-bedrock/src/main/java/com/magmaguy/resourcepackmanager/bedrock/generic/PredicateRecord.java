package com.magmaguy.resourcepackmanager.bedrock.generic;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;

/**
 * One Java&rarr;Geyser predicate. Mirrors Rainbow's GeyserConditionPredicate /
 * GeyserRangeDispatchPredicate / GeyserMatchPredicate.
 *
 * <p>See Option J plan, "Predicate walker design &mdash; translation table" for the full
 * Java predicate &rarr; Geyser predicate JSON mapping. Each implementation's
 * {@link #toGeyserJson()} produces the documented shape per
 * https://geysermc.org/wiki/geyser/custom-items/.
 */
public sealed interface PredicateRecord {

    /** Emits the Geyser-format JSON object for this predicate. */
    JsonObject toGeyserJson();

    /** {@code condition} predicate (boolean property like broken/damaged/has_component/etc.). */
    record Condition(String property, boolean expected, Map<String, JsonElement> extras) implements PredicateRecord {
        public Condition {
            extras = extras == null ? Map.of() : Map.copyOf(extras);
        }

        @Override
        public JsonObject toGeyserJson() {
            JsonObject o = new JsonObject();
            o.addProperty("type", "condition");
            o.addProperty("property", property);
            o.addProperty("expected", expected);
            for (Map.Entry<String, JsonElement> e : extras.entrySet()) o.add(e.getKey(), e.getValue());
            return o;
        }
    }

    /** {@code range_dispatch} predicate (numeric threshold + scale on a numeric property). */
    record RangeDispatch(String property, double threshold, double scale, Map<String, JsonElement> extras) implements PredicateRecord {
        public RangeDispatch {
            extras = extras == null ? Map.of() : Map.copyOf(extras);
        }

        @Override
        public JsonObject toGeyserJson() {
            JsonObject o = new JsonObject();
            o.addProperty("type", "range_dispatch");
            o.addProperty("property", property);
            o.addProperty("threshold", threshold);
            o.addProperty("scale", scale);
            for (Map.Entry<String, JsonElement> e : extras.entrySet()) o.add(e.getKey(), e.getValue());
            return o;
        }
    }

    /** {@code match} predicate (discrete value of a string-typed property). */
    record Match(String property, JsonElement value, Map<String, JsonElement> extras) implements PredicateRecord {
        public Match {
            extras = extras == null ? Map.of() : Map.copyOf(extras);
        }

        @Override
        public JsonObject toGeyserJson() {
            JsonObject o = new JsonObject();
            o.addProperty("type", "match");
            o.addProperty("property", property);
            o.add("value", value);
            for (Map.Entry<String, JsonElement> e : extras.entrySet()) o.add(e.getKey(), e.getValue());
            return o;
        }
    }
}
