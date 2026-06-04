package com.magmaguy.resourcepackmanager.bedrock.generic;

import com.google.gson.JsonPrimitive;
import com.magmaguy.resourcepackmanager.bedrock.util.BedrockShortName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Regression test for the Geyser "conflicts with another custom item definition with the
 * same bedrock identifier" warning flood.
 *
 * <p>A custom crossbow's item model branches on {@code charge_type} ({@code arrow} vs
 * {@code rocket}). Both branches resolved to the same custom model under the same base item
 * ({@code minecraft:crossbow}), so they produced two Geyser definitions that differed only by
 * predicate. The {@code bedrock_identifier} was hashed from (model, base) alone, so both
 * definitions got the same identifier and Geyser dropped the second one — five crossbow tiers
 * × the arrow/rocket pair gave exactly the five warnings seen in production.
 *
 * <p>The fix folds the predicate signature into the identifier hash. These tests pin both the
 * fix (predicate variants diverge) and the compatibility guarantee (predicate-free identifiers
 * are byte-identical to the legacy form, so existing Bedrock client caches stay valid).
 */
class BedrockIdentifierPredicateCollisionTest {

    private static final String MODEL = "elitemobs:gear/corrupted_crossbow";
    private static final String BASE = "minecraft:crossbow";

    private static List<PredicateRecord> chargeType(String value) {
        return List.of(new PredicateRecord.Match("charge_type", new JsonPrimitive(value), Map.of()));
    }

    private static String idFor(List<PredicateRecord> predicates) {
        return BedrockShortName.bedrockIdentifier(
                BedrockShortName.forBaseMapping(MODEL, BASE, MappedItemRegistry.predicateShape(predicates)));
    }

    @Test
    void arrowAndRocketVariants_getDistinctIdentifiers() {
        String arrow = idFor(chargeType("arrow"));
        String rocket = idFor(chargeType("rocket"));

        assertNotEquals(arrow, rocket,
                "charge_type=arrow and charge_type=rocket share a model+base but must not collide on bedrock_identifier");
    }

    @Test
    void unconditionalIdentifier_isByteIdenticalToLegacyForm() {
        // Empty predicate signature must hash exactly like the old two-arg call, or every
        // predicate-free item's identifier would churn and invalidate Bedrock client caches.
        String viaThreeArg = BedrockShortName.forBaseMapping(MODEL, BASE, MappedItemRegistry.predicateShape(List.of()));
        String viaLegacyTwoArg = BedrockShortName.forBaseMapping(MODEL, BASE);

        assertEquals(viaLegacyTwoArg, viaThreeArg,
                "unconditional mapping hash must be unchanged for cache stability");
    }

    @Test
    void unconditionalAndPredicatedVariants_allDiffer() {
        String unconditional = idFor(List.of());
        String arrow = idFor(chargeType("arrow"));
        String rocket = idFor(chargeType("rocket"));

        assertNotEquals(unconditional, arrow);
        assertNotEquals(unconditional, rocket);
        assertNotEquals(arrow, rocket);
    }

    @Test
    void sameModelBaseAndPredicate_isDeterministic() {
        // True duplicates (identical predicate) must still hash identically so the registry's
        // (base, id, predicateShape) dedup keeps catching them.
        assertEquals(idFor(chargeType("arrow")), idFor(chargeType("arrow")));
    }
}
