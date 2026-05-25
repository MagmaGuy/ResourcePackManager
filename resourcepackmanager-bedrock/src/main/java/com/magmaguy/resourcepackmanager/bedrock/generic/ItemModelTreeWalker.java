package com.magmaguy.resourcepackmanager.bedrock.generic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.magmaguy.resourcepackmanager.bedrock.BedrockLog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recursive walker over a Java 1.21.4+ items-definition model tree, producing one
 * {@link ResolvedLeaf} per terminal {@code minecraft:model} leaf reached.
 *
 * <p>Mirrors the switch structure in Rainbow's BedrockItemMapper.java:113-219, but
 * operates on raw JSON instead of Mojang's ItemModel.Unbaked types (which RSPM can't
 * access from a Bukkit plugin). Each leaf carries the ordered list of predicates
 * accumulated along its branch &mdash; those become the Geyser definition entry's
 * {@code predicate} array at emission time (Phase 7).
 *
 * <p>The {@link AssetResolver} parameter is currently unused but threaded through the
 * public API so later phases can resolve referenced models (e.g. nested item-defs)
 * without an API change.
 */
public final class ItemModelTreeWalker {

    private static final String NS_PREFIX = "minecraft:";

    /** One-shot guard so unverified-property warnings don't spam the log per JVM session. */
    private static final Set<String> unverifiedPropertiesLogged = new HashSet<>();

    private ItemModelTreeWalker() {
    }

    public static List<ResolvedLeaf> walk(ItemsDefinition def, AssetResolver resolver) {
        JsonObject root = def.root();
        if (root == null || !root.has("model") || !root.get("model").isJsonObject()) {
            BedrockLog.warn("[BedrockConverter] Items definition " + def.itemIdentifier()
                    + " has no model object; skipping");
            return List.of();
        }
        List<ResolvedLeaf> out = new ArrayList<>();
        walk(root.getAsJsonObject("model"), new ArrayList<>(), def, out);
        return out;
    }

    // -------------------------------------------------------------------------
    // Dispatch
    // -------------------------------------------------------------------------

    private static void walk(JsonObject node, List<PredicateRecord> stack,
                             ItemsDefinition def, List<ResolvedLeaf> out) {
        if (node == null) {
            BedrockLog.warn("[BedrockConverter] Null model node in " + def.itemIdentifier());
            return;
        }
        if (!node.has("type") || !node.get("type").isJsonPrimitive()) {
            BedrockLog.warn("[BedrockConverter] Model node has no type field in " + def.itemIdentifier());
            return;
        }
        String type = node.get("type").getAsString();
        switch (type) {
            case "minecraft:model" -> walkLeaf(node, stack, def, out);
            case "minecraft:condition" -> walkCondition(node, stack, def, out);
            case "minecraft:range_dispatch" -> walkRangeDispatch(node, stack, def, out);
            case "minecraft:select" -> walkSelect(node, stack, def, out);
            default -> BedrockLog.warn("[BedrockConverter] Unsupported model type '" + type
                    + "' in " + def.itemIdentifier());
        }
    }

    // -------------------------------------------------------------------------
    // minecraft:model — terminal leaf
    // -------------------------------------------------------------------------

    private static void walkLeaf(JsonObject node, List<PredicateRecord> stack,
                                 ItemsDefinition def, List<ResolvedLeaf> out) {
        if (!node.has("model") || !node.get("model").isJsonPrimitive()) {
            BedrockLog.warn("[BedrockConverter] minecraft:model leaf without 'model' field in "
                    + def.itemIdentifier());
            return;
        }
        String modelRef = node.get("model").getAsString();
        out.add(new ResolvedLeaf(modelRef, new ArrayList<>(stack)));
    }

    // -------------------------------------------------------------------------
    // minecraft:condition — boolean property gate
    // -------------------------------------------------------------------------

    private static void walkCondition(JsonObject node, List<PredicateRecord> stack,
                                      ItemsDefinition def, List<ResolvedLeaf> out) {
        String rawProperty = readProperty(node, def, "condition");
        if (rawProperty == null) return;
        String property = stripNamespace(rawProperty);

        // Phase G: `using_item` has NO real Geyser equivalent. Previous best-effort
        // (has_component(use_cooldown)) never fires on real bows/crossbows, and the
        // resulting duplicate-predicate-shape entries are rejected at registry dedup
        // time anyway. Instead, recurse ONLY into `on_false` (treating bow-at-rest as
        // the unconditionally-emitted state) and drop the `on_true` subtree entirely.
        // Bow ends up with exactly one leaf (the idle/rest model, no predicates).
        if (property.equals("using_item")) {
            logUnverifiedOnce("using_item",
                    "Java condition.using_item has no Geyser equivalent; "
                            + "emitting only the on_false (idle/rest) branch without predicate");
            if (node.has("on_false") && node.get("on_false").isJsonObject()) {
                walk(node.getAsJsonObject("on_false"), new ArrayList<>(stack), def, out);
            }
            return;
        }

        // Property-specific predicate factories. Each returns extras keyed by Geyser-side property
        // name; the boolean (expected) is filled in per branch below.
        Map<String, JsonElement> trueExtras = new LinkedHashMap<>();
        Map<String, JsonElement> falseExtras = new LinkedHashMap<>();
        String geyserProperty;

        switch (property) {
            case "broken", "damaged", "fishing_rod_cast" -> geyserProperty = property;
            case "has_component" -> {
                geyserProperty = "has_component";
                if (!node.has("component") || !node.get("component").isJsonPrimitive()) {
                    BedrockLog.warn("[BedrockConverter] condition.has_component missing 'component' field in "
                            + def.itemIdentifier() + "; skipping branch");
                    return;
                }
                JsonPrimitive component = new JsonPrimitive(node.get("component").getAsString());
                trueExtras.put("component", component);
                falseExtras.put("component", component);
            }
            case "custom_model_data" -> {
                geyserProperty = "custom_model_data";
                int index = readOptionalInt(node, "index", 0);
                JsonPrimitive idxPrim = new JsonPrimitive(index);
                trueExtras.put("index", idxPrim);
                falseExtras.put("index", idxPrim);
            }
            default -> {
                BedrockLog.warn("[BedrockConverter] Unsupported conditional property '" + rawProperty
                        + "' in " + def.itemIdentifier() + "; skipping both branches");
                return;
            }
        }

        PredicateRecord onTruePredicate = new PredicateRecord.Condition(geyserProperty, true, trueExtras);
        PredicateRecord onFalsePredicate = new PredicateRecord.Condition(geyserProperty, false, falseExtras);

        if (node.has("on_true") && node.get("on_true").isJsonObject()) {
            List<PredicateRecord> nextStack = new ArrayList<>(stack);
            nextStack.add(onTruePredicate);
            walk(node.getAsJsonObject("on_true"), nextStack, def, out);
        }
        if (node.has("on_false") && node.get("on_false").isJsonObject()) {
            List<PredicateRecord> nextStack = new ArrayList<>(stack);
            nextStack.add(onFalsePredicate);
            walk(node.getAsJsonObject("on_false"), nextStack, def, out);
        }
    }

    // -------------------------------------------------------------------------
    // minecraft:range_dispatch — numeric threshold branching
    // -------------------------------------------------------------------------

    private static void walkRangeDispatch(JsonObject node, List<PredicateRecord> stack,
                                          ItemsDefinition def, List<ResolvedLeaf> out) {
        String rawProperty = readProperty(node, def, "range_dispatch");
        if (rawProperty == null) return;
        String property = stripNamespace(rawProperty);

        // Phase G: `use_duration` has NO real Geyser equivalent (bow draw stages). The
        // previous best-effort (range_dispatch on custom_model_data) never fires on real
        // bow/crossbow items. Drop all threshold entries and recurse ONLY into `fallback`
        // (the bow-at-rest idle stage). Combined with the on_false-only `using_item`
        // handling above, a bow ends up with exactly one leaf (idle/rest, no predicates).
        if (property.equals("use_duration")) {
            logUnverifiedOnce("use_duration",
                    "Java range_dispatch.use_duration has no Geyser equivalent; "
                            + "emitting only the fallback (idle/rest) branch without predicate");
            if (node.has("fallback") && node.get("fallback").isJsonObject()) {
                walk(node.getAsJsonObject("fallback"), new ArrayList<>(stack), def, out);
            }
            return;
        }

        double scale = readOptionalDouble(node, "scale", 1.0);

        // Property mapping to (geyser property name, builder of per-entry extras).
        String geyserProperty;
        boolean propertyOk = true;
        JsonElement normalize = node.has("normalize") ? node.get("normalize") : null;
        int indexExtra = readOptionalInt(node, "index", 0);

        switch (property) {
            case "damage", "count" -> geyserProperty = property;
            case "bundle_fullness" -> geyserProperty = "bundle_fullness";
            case "custom_model_data" -> geyserProperty = "custom_model_data";
            default -> {
                BedrockLog.warn("[BedrockConverter] Unsupported range_dispatch property '" + rawProperty
                        + "' in " + def.itemIdentifier() + "; mapping only fallback if present");
                propertyOk = false;
                geyserProperty = null;
            }
        }

        if (propertyOk) {
            if (!node.has("entries") || !node.get("entries").isJsonArray()) {
                BedrockLog.warn("[BedrockConverter] range_dispatch missing 'entries' array in "
                        + def.itemIdentifier());
            } else {
                JsonArray entries = node.getAsJsonArray("entries");
                for (JsonElement entryEl : entries) {
                    if (!entryEl.isJsonObject()) {
                        BedrockLog.warn("[BedrockConverter] range_dispatch entry is not an object in "
                                + def.itemIdentifier());
                        continue;
                    }
                    JsonObject entry = entryEl.getAsJsonObject();
                    if (!entry.has("threshold") || !entry.get("threshold").isJsonPrimitive()) {
                        BedrockLog.warn("[BedrockConverter] range_dispatch entry missing 'threshold' in "
                                + def.itemIdentifier());
                        continue;
                    }
                    if (!entry.has("model") || !entry.get("model").isJsonObject()) {
                        BedrockLog.warn("[BedrockConverter] range_dispatch entry missing 'model' object in "
                                + def.itemIdentifier());
                        continue;
                    }
                    double threshold = entry.get("threshold").getAsDouble();

                    Map<String, JsonElement> extras = new LinkedHashMap<>();
                    switch (property) {
                        case "custom_model_data" -> extras.put("index", new JsonPrimitive(indexExtra));
                        case "damage", "count" -> {
                            if (normalize != null) extras.put("normalize", normalize);
                            else extras.put("normalize", new JsonPrimitive(true));
                        }
                        case "bundle_fullness" -> { /* no extras */ }
                        default -> { /* unreachable; propertyOk gates */ }
                    }

                    PredicateRecord predicate = new PredicateRecord.RangeDispatch(
                            geyserProperty, threshold, scale, extras);
                    List<PredicateRecord> nextStack = new ArrayList<>(stack);
                    nextStack.add(predicate);
                    walk(entry.getAsJsonObject("model"), nextStack, def, out);
                }
            }
        }

        // Fallback is walked with NO added predicate (matches BedrockItemMapper.java:181).
        if (node.has("fallback") && node.get("fallback").isJsonObject()) {
            walk(node.getAsJsonObject("fallback"), new ArrayList<>(stack), def, out);
        }
    }

    // -------------------------------------------------------------------------
    // minecraft:select — discrete-value branching
    // -------------------------------------------------------------------------

    private static void walkSelect(JsonObject node, List<PredicateRecord> stack,
                                   ItemsDefinition def, List<ResolvedLeaf> out) {
        String rawProperty = readProperty(node, def, "select");
        if (rawProperty == null) return;
        String property = stripNamespace(rawProperty);

        JsonArray cases = node.has("cases") && node.get("cases").isJsonArray()
                ? node.getAsJsonArray("cases") : new JsonArray();
        boolean hasFallback = node.has("fallback") && node.get("fallback").isJsonObject();

        // Special case: display_context — only keep the GUI case, drop all predicates.
        // Mirrors BedrockItemMapper.java:199-207.
        if (property.equals("display_context")) {
            boolean guiCaseFound = false;
            for (JsonElement caseEl : cases) {
                if (!caseEl.isJsonObject()) continue;
                JsonObject caseObj = caseEl.getAsJsonObject();
                if (caseMatchesWhen(caseObj, "gui") && caseObj.has("model")
                        && caseObj.get("model").isJsonObject()) {
                    guiCaseFound = true;
                    walk(caseObj.getAsJsonObject("model"), new ArrayList<>(stack), def, out);
                    // Don't break — defensive: if multiple gui cases exist, walk each. In practice
                    // there's only one.
                }
            }
            if (!guiCaseFound && hasFallback) {
                walk(node.getAsJsonObject("fallback"), new ArrayList<>(stack), def, out);
            }
            return;
        }

        // Normal case: each `when` value becomes its own Match predicate; walk model per value.
        boolean supportedProperty = switch (property) {
            case "charge_type", "trim_material", "context_dimension", "custom_model_data" -> true;
            default -> false;
        };

        if (!supportedProperty) {
            BedrockLog.warn("[BedrockConverter] Unsupported select property '" + rawProperty
                    + "' in " + def.itemIdentifier() + "; mapping only fallback if present");
        } else {
            int indexExtra = readOptionalInt(node, "index", 0);
            for (JsonElement caseEl : cases) {
                if (!caseEl.isJsonObject()) {
                    BedrockLog.warn("[BedrockConverter] select case is not an object in "
                            + def.itemIdentifier());
                    continue;
                }
                JsonObject caseObj = caseEl.getAsJsonObject();
                if (!caseObj.has("model") || !caseObj.get("model").isJsonObject()) {
                    BedrockLog.warn("[BedrockConverter] select case missing 'model' object in "
                            + def.itemIdentifier());
                    continue;
                }
                List<JsonElement> whenValues = readWhenValues(caseObj);
                if (whenValues.isEmpty()) {
                    BedrockLog.warn("[BedrockConverter] select case missing 'when' value in "
                            + def.itemIdentifier());
                    continue;
                }
                for (JsonElement whenValue : whenValues) {
                    Map<String, JsonElement> extras = new LinkedHashMap<>();
                    if (property.equals("custom_model_data")) {
                        extras.put("index", new JsonPrimitive(indexExtra));
                    }
                    PredicateRecord predicate = new PredicateRecord.Match(property, whenValue, extras);
                    List<PredicateRecord> nextStack = new ArrayList<>(stack);
                    nextStack.add(predicate);
                    walk(caseObj.getAsJsonObject("model"), nextStack, def, out);
                }
            }
        }

        // Fallback walked with NO added predicate (matches BedrockItemMapper.java:218).
        if (hasFallback) {
            walk(node.getAsJsonObject("fallback"), new ArrayList<>(stack), def, out);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Reads a {@code property} string field; emits a warning and returns null if missing/malformed. */
    private static String readProperty(JsonObject node, ItemsDefinition def, String nodeKind) {
        if (!node.has("property") || !node.get("property").isJsonPrimitive()) {
            BedrockLog.warn("[BedrockConverter] " + nodeKind + " node missing 'property' field in "
                    + def.itemIdentifier());
            return null;
        }
        return node.get("property").getAsString();
    }

    private static String stripNamespace(String raw) {
        return raw.startsWith(NS_PREFIX) ? raw.substring(NS_PREFIX.length()) : raw;
    }

    private static int readOptionalInt(JsonObject node, String key, int fallback) {
        if (!node.has(key) || !node.get(key).isJsonPrimitive()) return fallback;
        try {
            return node.get(key).getAsInt();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double readOptionalDouble(JsonObject node, String key, double fallback) {
        if (!node.has(key) || !node.get(key).isJsonPrimitive()) return fallback;
        try {
            return node.get(key).getAsDouble();
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * Reads a {@code when} field from a select case. Per the Java 1.21.4+ format, this may be a
     * single primitive (string/number/bool) or an array of primitives — return all values.
     */
    private static List<JsonElement> readWhenValues(JsonObject caseObj) {
        if (!caseObj.has("when")) return List.of();
        JsonElement when = caseObj.get("when");
        if (when.isJsonPrimitive()) return List.of(when);
        if (when.isJsonArray()) {
            List<JsonElement> values = new ArrayList<>();
            for (JsonElement v : when.getAsJsonArray()) {
                if (v.isJsonPrimitive()) values.add(v);
            }
            return values;
        }
        return List.of();
    }

    /** True if any value in the case's {@code when} (primitive or array) equals {@code target}. */
    private static boolean caseMatchesWhen(JsonObject caseObj, String target) {
        for (JsonElement v : readWhenValues(caseObj)) {
            if (v.isJsonPrimitive() && v.getAsString().equals(target)) return true;
        }
        return false;
    }

    private static void logUnverifiedOnce(String key, String message) {
        if (unverifiedPropertiesLogged.add(key)) {
            BedrockLog.warn("[BedrockConverter] " + message);
        }
    }
}
