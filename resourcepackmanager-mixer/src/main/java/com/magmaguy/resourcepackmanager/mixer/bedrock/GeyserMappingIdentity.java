package com.magmaguy.resourcepackmanager.mixer.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Computes the identity under which Geyser considers two custom-item definitions to be
 * <em>in conflict</em>: same Java item, same {@code model}, and predicate lists that parse
 * to equal predicate objects ({@code CustomItemRegistryPopulator.checkPredicate}, verified
 * against Geyser 2.11.2-b1233). Geyser registers only the first such definition and logs
 * "Not registering custom item definition (...): conflicts with custom item definition
 * (...): both entries have the same predicates" for every later one.
 *
 * <p>This is deliberately NOT the same thing as the {@code bedrock_identifier}: RSPM's
 * identifier hash includes the resolved Java model ref, which Geyser never compares. Two
 * mappings with different identifiers (different source models) can still be one identity
 * here — e.g. two merged packs claiming the same custom_model_data on the same item, or a
 * dual-format pack whose modern items definition and legacy override describe the same
 * matcher. Both the backend registry and the proxy-side mappings merger deduplicate by
 * this identity so Geyser never has to reject an entry at boot.
 *
 * <p>Canonicalisation mirrors Geyser's mappings readers exactly where equality depends on
 * parse-time folding:
 * <ul>
 *   <li>{@code type=legacy} parses to model = the Java item itself plus a single
 *       {@code range_dispatch} predicate on custom_model_data
 *       ({@code LegacyDefinitionReader} → {@code ItemRangeDispatchPredicate.legacyCustomModelData}).</li>
 *   <li>{@code range_dispatch} folds {@code threshold / scale} (scale defaults to 1) into a
 *       single value ({@code ItemRangeDispatchProperty.readThreshold}); custom_model_data
 *       additionally casts to float and keys on {@code index} (default 0); damage/count cast
 *       to int when {@code normalize} is false (the default).</li>
 *   <li>{@code condition}'s {@code expected} defaults to true.</li>
 *   <li>Predicate-list comparison is order-insensitive (Geyser checks size + containsAll),
 *       so canonical predicate strings are sorted before joining.</li>
 * </ul>
 */
public final class GeyserMappingIdentity {

    private GeyserMappingIdentity() {}

    /**
     * Identity of one emitted mappings-file entry (the JSON shape written by
     * GenericGeyserMappingBuilder), or {@code null} when the entry is malformed enough
     * that no identity can be computed — callers must then leave it un-deduplicated
     * rather than guess.
     */
    public static String of(String baseItem, JsonObject definition) {
        if (definition == null) return null;
        String type = stringOrNull(definition.get("type"));
        if ("legacy".equals(type)) {
            Double cmd = doubleOrNull(definition.get("custom_model_data"));
            if (cmd == null) return null;
            return forLegacy(baseItem, cmd);
        }
        if ("definition".equals(type)) {
            String model = stringOrNull(definition.get("model"));
            if (model == null) return null;
            List<JsonObject> predicates = new ArrayList<>();
            JsonElement predicateElement = definition.get("predicate");
            if (predicateElement != null) {
                if (predicateElement.isJsonArray()) {
                    for (JsonElement p : predicateElement.getAsJsonArray()) {
                        if (!p.isJsonObject()) return null;
                        predicates.add(p.getAsJsonObject());
                    }
                } else if (predicateElement.isJsonObject()) {
                    // Geyser accepts a single predicate object as well as an array.
                    predicates.add(predicateElement.getAsJsonObject());
                } else {
                    return null;
                }
            }
            return forModern(baseItem, model, predicates);
        }
        // Unknown/absent type: no safe identity.
        return null;
    }

    /** Identity of a modern {@code type=definition} entry. */
    public static String forModern(String baseItem, String model, Iterable<JsonObject> predicates) {
        List<String> canonical = new ArrayList<>();
        for (JsonObject predicate : predicates) canonical.add(canonicalPredicate(predicate));
        canonical.sort(String::compareTo);
        return normalizeIdentifier(baseItem) + "\n" + normalizeIdentifier(model)
                + "\n" + String.join("\n", canonical);
    }

    /**
     * Identity of a {@code type=legacy} entry. Geyser turns it into a definition whose
     * model is the Java item itself and whose sole predicate is
     * {@code legacyCustomModelData(cmd)} — byte-identical to a modern range_dispatch on
     * custom_model_data with scale 1 and index 0, which is why a dual-format pack's two
     * entries can collide.
     */
    public static String forLegacy(String baseItem, double customModelData) {
        // Same float fold the modern custom_model_data branch performs, so the two forms
        // produce the same canonical predicate string for the same value.
        String predicate = "range_dispatch|custom_model_data|"
                + canonicalNumber((float) customModelData) + "|i0";
        return normalizeIdentifier(baseItem) + "\n" + normalizeIdentifier(baseItem)
                + "\n" + predicate;
    }

    /**
     * Human-readable matcher for dedup log lines: the part of the identity an operator can
     * act on ("custom_model_data 101", "model minecraft:paper [range_dispatch|damage|5|abs]").
     */
    public static String describeMatcher(String baseItem, JsonObject definition) {
        String type = definition == null ? null : stringOrNull(definition.get("type"));
        if ("legacy".equals(type)) {
            Double cmd = doubleOrNull(definition.get("custom_model_data"));
            return "custom_model_data " + (cmd == null ? "?" : canonicalNumber(cmd));
        }
        if ("definition".equals(type)) {
            String identity = of(baseItem, definition);
            if (identity != null) {
                String[] parts = identity.split("\n", 3);
                String predicates = parts.length < 3 || parts[2].isEmpty()
                        ? "no predicates" : parts[2].replace("\n", ", ");
                return "model " + parts[1] + " [" + predicates + "]";
            }
        }
        return "unrecognized matcher";
    }

    /**
     * Joins dedup notes into the single summary line both emitters log, capping the tail so
     * a pathological pack can't produce a megabyte log line.
     */
    public static String summarizeNotes(List<String> notes, int cap) {
        if (notes.size() <= cap) return String.join("; ", notes);
        return String.join("; ", notes.subList(0, cap))
                + "; … (+" + (notes.size() - cap) + " more)";
    }

    /**
     * One predicate object folded the way Geyser's readers fold it. Unknown types or
     * properties fall back to the full sorted JSON so they still dedup on byte-identical
     * content but never unify two entries Geyser might treat as distinct. Public because
     * the backend registry also uses it to describe a matcher in its dedup summary.
     */
    public static String canonicalPredicate(JsonObject predicate) {
        String type = stripMinecraftNamespace(stringOr(predicate.get("type"), ""));
        switch (type) {
            case "range_dispatch" -> {
                String property = stripMinecraftNamespace(stringOr(predicate.get("property"), ""));
                Double threshold = doubleOrNull(predicate.get("threshold"));
                if (threshold == null) break; // malformed; raw fallback
                double scale = doubleOr(predicate.get("scale"), 1.0);
                double folded = threshold / scale;
                switch (property) {
                    case "custom_model_data" -> {
                        long index = (long) doubleOr(predicate.get("index"), 0);
                        return "range_dispatch|custom_model_data|"
                                + canonicalNumber((float) folded) + "|i" + index;
                    }
                    case "damage", "count" -> {
                        boolean normalize = booleanOr(predicate.get("normalize"), false);
                        // Non-normalized damage/count is cast to int at parse time, so
                        // thresholds 5.2 and 5.9 are the same predicate to Geyser.
                        String value = normalize
                                ? canonicalNumber(folded) + "|norm"
                                : canonicalNumber((int) folded) + "|abs";
                        return "range_dispatch|" + property + "|" + value;
                    }
                    case "bundle_fullness" -> {
                        return "range_dispatch|bundle_fullness|" + canonicalNumber(folded);
                    }
                    default -> { /* unmapped property: raw fallback below */ }
                }
            }
            case "condition" -> {
                String property = stripMinecraftNamespace(stringOr(predicate.get("property"), ""));
                boolean expected = booleanOr(predicate.get("expected"), true);
                return "condition|" + property + "|" + expected + "|"
                        + canonicalExtras(predicate, "type", "property", "expected");
            }
            case "match" -> {
                String property = stripMinecraftNamespace(stringOr(predicate.get("property"), ""));
                JsonElement value = predicate.get("value");
                return "match|" + property + "|" + (value == null ? "" : canonicalJson(value))
                        + "|" + canonicalExtras(predicate, "type", "property", "value");
            }
            default -> { /* raw fallback below */ }
        }
        return "raw|" + canonicalJson(predicate);
    }

    /** Remaining predicate fields (reader "extras"), key-sorted for determinism. */
    private static String canonicalExtras(JsonObject predicate, String... consumedKeys) {
        TreeMap<String, JsonElement> extras = new TreeMap<>();
        outer:
        for (Map.Entry<String, JsonElement> e : predicate.entrySet()) {
            for (String consumed : consumedKeys) {
                if (consumed.equals(e.getKey())) continue outer;
            }
            extras.put(e.getKey(), e.getValue());
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, JsonElement> e : extras.entrySet()) {
            sb.append(e.getKey()).append('=').append(canonicalJson(e.getValue())).append(',');
        }
        return sb.toString();
    }

    /**
     * Deterministic JSON rendering: object keys sorted, numbers folded through
     * {@link #canonicalNumber(double)} so {@code 0} and {@code 0.0} compare equal the way
     * they do after Geyser parses them.
     */
    private static String canonicalJson(JsonElement element) {
        if (element == null || element.isJsonNull()) return "null";
        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isNumber()) return canonicalNumber(primitive.getAsDouble());
            return primitive.toString();
        }
        if (element.isJsonArray()) {
            StringBuilder sb = new StringBuilder("[");
            for (JsonElement child : element.getAsJsonArray()) {
                sb.append(canonicalJson(child)).append(',');
            }
            return sb.append(']').toString();
        }
        TreeMap<String, JsonElement> sorted = new TreeMap<>();
        for (Map.Entry<String, JsonElement> e : element.getAsJsonObject().entrySet()) {
            sorted.put(e.getKey(), e.getValue());
        }
        StringBuilder sb = new StringBuilder("{");
        for (Map.Entry<String, JsonElement> e : sorted.entrySet()) {
            sb.append(e.getKey()).append(':').append(canonicalJson(e.getValue())).append(',');
        }
        return sb.append('}').toString();
    }

    /** Integral values print without a decimal part so 101, 101.0 and 101f agree. */
    static String canonicalNumber(double value) {
        if (Double.isFinite(value) && value == Math.rint(value)
                && Math.abs(value) < (double) (1L << 53)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    private static String normalizeIdentifier(String identifier) {
        if (identifier == null) return "";
        return identifier.indexOf(':') >= 0 ? identifier : "minecraft:" + identifier;
    }

    private static String stripMinecraftNamespace(String value) {
        return value.startsWith("minecraft:") ? value.substring("minecraft:".length()) : value;
    }

    private static String stringOrNull(JsonElement element) {
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private static String stringOr(JsonElement element, String fallback) {
        String value = stringOrNull(element);
        return value == null ? fallback : value;
    }

    private static Double doubleOrNull(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        return element.getAsDouble();
    }

    private static double doubleOr(JsonElement element, double fallback) {
        Double value = doubleOrNull(element);
        return value == null ? fallback : value;
    }

    private static boolean booleanOr(JsonElement element, boolean fallback) {
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isBoolean()) {
            return fallback;
        }
        return element.getAsBoolean();
    }
}
