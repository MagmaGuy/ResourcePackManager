package com.magmaguy.resourcepackmanager.bedrock.generic;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The base items a resource pack itself puts its custom models on.
 *
 * <p>A custom item model such as {@code modelengine:ghoul/head} does not say which Java item
 * carries it, so {@link BaseItemResolver} has to guess and registers it under every candidate
 * (ten for the generic fallback). Packs often say it elsewhere, though: ModelEngine also writes
 * {@code assets/minecraft/items/leather_horse_armor.json}, whose custom-model-data branches
 * point at the same bone models. That file is the producer naming its carrier, so a custom
 * definition whose models all appear there maps under {@code leather_horse_armor} alone.
 * On one ModelEngine network the guesses added up to 31,970 Geyser custom items, about
 * twelve per bone, and every Bedrock client that joined crashed.
 *
 * <p>Evidence comes from definitions that name their base item: modern
 * {@code assets/minecraft/items/<item>.json} files and legacy
 * {@code assets/minecraft/models/item/<item>.json} overrides. Only their conditional branches
 * count; an unconditional branch is the vanilla item's own look. Bases Geyser cannot override
 * are not evidence, so such a model keeps today's guesses.
 */
public final class PackCarrierIndex {

    private static final PackCarrierIndex EMPTY = new PackCarrierIndex(Map.of());

    private final Map<String, Set<String>> carriersByModel;

    private PackCarrierIndex(Map<String, Set<String>> carriersByModel) {
        this.carriersByModel = carriersByModel;
    }

    /**
     * @param definitions every scanned items definition
     * @param leaves      the walked leaves of each definition, at the same index
     */
    public static PackCarrierIndex build(List<ItemsDefinition> definitions, List<List<ResolvedLeaf>> leaves) {
        if (definitions.size() != leaves.size())
            throw new IllegalArgumentException("Each definition needs its leaves: "
                    + definitions.size() + " definitions, " + leaves.size() + " leaf lists");
        Map<String, Set<String>> carriers = new HashMap<>();
        for (int i = 0; i < definitions.size(); i++) {
            ItemsDefinition def = definitions.get(i);
            List<String> bases;
            if (def.hasExplicitBaseItems()) bases = def.explicitBaseItems();
            else if (def.isRootVanillaItem()) bases = List.of(def.itemIdentifier());
            else continue;
            for (ResolvedLeaf leaf : leaves.get(i)) {
                if (leaf.predicates().isEmpty()) continue;
                for (String base : bases) {
                    if (!GeyserBaseItemCompatibility.supportsCustomItemOverride(base)) continue;
                    carriers.computeIfAbsent(leaf.modelRef(), model -> new LinkedHashSet<>()).add(base);
                }
            }
        }
        return carriers.isEmpty() ? EMPTY : new PackCarrierIndex(carriers);
    }

    /**
     * The base items the pack puts every one of these models on, or an empty list when any
     * of them has no such evidence. A definition the pack covers only in part keeps its
     * guessed bases, because the uncovered models may need them.
     */
    public List<String> carriersFor(List<ResolvedLeaf> leaves) {
        if (leaves.isEmpty() || carriersByModel.isEmpty()) return List.of();
        Set<String> union = new LinkedHashSet<>();
        for (ResolvedLeaf leaf : leaves) {
            Set<String> carriers = carriersByModel.get(leaf.modelRef());
            if (carriers == null) return List.of();
            union.addAll(carriers);
        }
        return List.copyOf(union);
    }
}
