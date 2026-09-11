package com.magmaguy.resourcepackmanager.mixer.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies the canonical identity matches Geyser's conflict semantics
 * (CustomItemRegistryPopulator.checkPredicate + the v2 mappings readers, verified against
 * Geyser 2.11.2-b1233): two definitions conflict iff same item, same model, and predicate
 * lists that parse equal — with legacy entries converting to (model = the item itself,
 * one custom_model_data range_dispatch predicate).
 */
class GeyserMappingIdentityTest {

    private static JsonObject rangeDispatch(String property, double threshold, Double scale, Integer index) {
        JsonObject p = new JsonObject();
        p.addProperty("type", "range_dispatch");
        p.addProperty("property", property);
        p.addProperty("threshold", threshold);
        if (scale != null) p.addProperty("scale", scale);
        if (index != null) p.addProperty("index", index);
        return p;
    }

    private static JsonObject definitionEntry(String id, String model, JsonObject... predicates) {
        JsonObject def = new JsonObject();
        def.addProperty("type", "definition");
        def.addProperty("bedrock_identifier", id);
        if (predicates.length > 0) {
            JsonArray arr = new JsonArray();
            for (JsonObject p : predicates) arr.add(p);
            def.add("predicate", arr);
        }
        def.addProperty("model", model);
        return def;
    }

    private static JsonObject legacyEntry(String id, double cmd) {
        JsonObject def = new JsonObject();
        def.addProperty("type", "legacy");
        def.addProperty("custom_model_data", cmd);
        def.addProperty("bedrock_identifier", id);
        return def;
    }

    @Test
    void sameModelAndPredicatesCollideAcrossDifferentIdentifiers() {
        // The patron-reported case: two entries whose bedrock identifiers differ (they
        // hash different resolved source models) but that are one matcher to Geyser.
        String a = GeyserMappingIdentity.of("minecraft:paper",
                definitionEntry("r:aaaaaaaa", "minecraft:paper", rangeDispatch("custom_model_data", 101, 1.0, 0)));
        String b = GeyserMappingIdentity.of("minecraft:paper",
                definitionEntry("r:bbbbbbbb", "minecraft:paper", rangeDispatch("custom_model_data", 101, 1.0, 0)));
        assertEquals(a, b);
    }

    @Test
    void legacyMatchesEquivalentModernDefinition() {
        // Dual-format packs: Geyser parses type=legacy into (model = the item, one
        // legacyCustomModelData predicate), colliding with the modern form.
        String legacy = GeyserMappingIdentity.of("minecraft:paper", legacyEntry("r:cccccccc", 101));
        String modern = GeyserMappingIdentity.of("minecraft:paper",
                definitionEntry("r:dddddddd", "minecraft:paper", rangeDispatch("custom_model_data", 101, 1.0, 0)));
        assertEquals(legacy, modern);
    }

    @Test
    void thresholdIsFoldedByScaleLikeGeyser() {
        // ItemRangeDispatchProperty.readThreshold computes threshold / scale.
        String explicit = GeyserMappingIdentity.of("minecraft:paper",
                definitionEntry("r:a", "minecraft:paper", rangeDispatch("custom_model_data", 202, 2.0, 0)));
        String folded = GeyserMappingIdentity.of("minecraft:paper",
                definitionEntry("r:b", "minecraft:paper", rangeDispatch("custom_model_data", 101, null, null)));
        assertEquals(explicit, folded);
    }

    @Test
    void distinctMatchersStayDistinct() {
        String cmd101 = GeyserMappingIdentity.of("minecraft:paper",
                definitionEntry("r:a", "minecraft:paper", rangeDispatch("custom_model_data", 101, null, null)));
        String cmd102 = GeyserMappingIdentity.of("minecraft:paper",
                definitionEntry("r:b", "minecraft:paper", rangeDispatch("custom_model_data", 102, null, null)));
        String otherModel = GeyserMappingIdentity.of("minecraft:paper",
                definitionEntry("r:c", "elitemobs:gear/sword", rangeDispatch("custom_model_data", 101, null, null)));
        String otherIndex = GeyserMappingIdentity.of("minecraft:paper",
                definitionEntry("r:d", "minecraft:paper", rangeDispatch("custom_model_data", 101, null, 1)));
        assertNotEquals(cmd101, cmd102);
        assertNotEquals(cmd101, otherModel);
        assertNotEquals(cmd101, otherIndex);
    }

    @Test
    void predicateOrderDoesNotMatter() {
        // Geyser compares predicate lists by size + containsAll, not order.
        JsonObject broken = new JsonObject();
        broken.addProperty("type", "condition");
        broken.addProperty("property", "broken");
        broken.addProperty("expected", true);
        JsonObject damage = rangeDispatch("damage", 5, null, null);
        String forward = GeyserMappingIdentity.of("minecraft:bow",
                definitionEntry("r:a", "minecraft:bow", broken, damage));
        String backward = GeyserMappingIdentity.of("minecraft:bow",
                definitionEntry("r:b", "minecraft:bow", damage, broken));
        assertEquals(forward, backward);
    }

    @Test
    void conditionExpectedDefaultsToTrue() {
        JsonObject explicit = new JsonObject();
        explicit.addProperty("type", "condition");
        explicit.addProperty("property", "broken");
        explicit.addProperty("expected", true);
        JsonObject implicit = new JsonObject();
        implicit.addProperty("type", "condition");
        implicit.addProperty("property", "broken");
        assertEquals(
                GeyserMappingIdentity.of("minecraft:bow", definitionEntry("r:a", "minecraft:bow", explicit)),
                GeyserMappingIdentity.of("minecraft:bow", definitionEntry("r:b", "minecraft:bow", implicit)));
    }

    @Test
    void nonNormalizedDamageTruncatesToIntLikeGeyser() {
        // Without normalize, Geyser casts threshold/scale to int, so 5.2 and 5.9 parse
        // to the same predicate.
        String a = GeyserMappingIdentity.of("minecraft:bow",
                definitionEntry("r:a", "minecraft:bow", rangeDispatch("damage", 5.2, null, null)));
        String b = GeyserMappingIdentity.of("minecraft:bow",
                definitionEntry("r:b", "minecraft:bow", rangeDispatch("damage", 5.9, null, null)));
        assertEquals(a, b);
    }

    @Test
    void malformedEntriesGetNoIdentity() {
        JsonObject typeless = new JsonObject();
        typeless.addProperty("bedrock_identifier", "r:a");
        assertNull(GeyserMappingIdentity.of("minecraft:paper", typeless));
        JsonObject legacyWithoutCmd = new JsonObject();
        legacyWithoutCmd.addProperty("type", "legacy");
        assertNull(GeyserMappingIdentity.of("minecraft:paper", legacyWithoutCmd));
    }

    @Test
    void summarizeNotesCapsTheTail() {
        List<String> notes = List.of("a", "b", "c", "d");
        assertEquals("a; b; c; d", GeyserMappingIdentity.summarizeNotes(notes, 8));
        assertEquals("a; b; … (+2 more)", GeyserMappingIdentity.summarizeNotes(notes, 2));
    }
}
