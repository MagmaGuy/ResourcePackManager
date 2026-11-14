package com.magmaguy.resourcepackmanager.bedrock.generic;

import java.util.List;

/**
 * Result of {@link ItemModelTreeWalker} reaching a {@code minecraft:model} leaf:
 * the model reference (e.g. {@code "elitemobs:gear/bronze_sword"}) and the ordered
 * list of {@link PredicateRecord}s that gate this leaf (empty list = unconditional).
 *
 * <p>Each leaf becomes one Geyser definition entry per candidate base item at
 * mapping-emission time (Phase 7).
 *
 * @param dyeTintDefault the {@code default} colour of the leaf's {@code minecraft:dye} tint as
 *                       a 0xRRGGBB value, or {@code null} when the leaf is not dyed. Only such
 *                       leaves are made dyeable on Bedrock, where the tint covers the whole icon.
 */
public record ResolvedLeaf(String modelRef, List<PredicateRecord> predicates, Integer dyeTintDefault) {
    public ResolvedLeaf {
        predicates = List.copyOf(predicates);
    }

    public ResolvedLeaf(String modelRef, List<PredicateRecord> predicates) {
        this(modelRef, predicates, null);
    }
}
