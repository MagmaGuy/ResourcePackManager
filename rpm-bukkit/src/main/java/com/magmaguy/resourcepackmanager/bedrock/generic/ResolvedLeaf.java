package com.magmaguy.resourcepackmanager.bedrock.generic;

import java.util.List;

/**
 * Result of {@link ItemModelTreeWalker} reaching a {@code minecraft:model} leaf:
 * the model reference (e.g. {@code "elitemobs:gear/bronze_sword"}) and the ordered
 * list of {@link PredicateRecord}s that gate this leaf (empty list = unconditional).
 *
 * <p>Each leaf becomes one Geyser definition entry per candidate base item at
 * mapping-emission time (Phase 7).
 */
public record ResolvedLeaf(String modelRef, List<PredicateRecord> predicates) {
    public ResolvedLeaf {
        predicates = List.copyOf(predicates);
    }
}
