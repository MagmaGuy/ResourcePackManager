package com.magmaguy.resourcepackmanager.bedrock.generic;

import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the custom items that a Geyser definition stands for, the way Geyser itself picks a
 * definition for a stack:
 * <ul>
 *   <li>an item-model definition ({@code assets/<ns>/items/<path>.json}) stands for the stacks
 *       whose {@code item_model} is that id;</li>
 *   <li>a legacy custom-model-data definition stands for the stacks on its base item with that
 *       custom-model-data value;</li>
 *   <li>a branch of a vanilla item's own definition gated by one {@code custom_model_data}
 *       threshold stands for the stacks on that item whose value selects that branch, the
 *       highest threshold not above it.</li>
 * </ul>
 * Stacks on another base item never match: Geyser keys definitions by the Java item the client
 * receives.
 */
public final class ItemComponentIndex {

    public static final ItemComponentIndex EMPTY = new ItemComponentIndex(List.of());

    private final Map<String, List<ItemComponentSnapshot>> byModel = new HashMap<>();
    private final Map<String, List<ItemComponentSnapshot>> byBaseWithCustomModelData = new HashMap<>();
    private final int size;

    public ItemComponentIndex(List<ItemComponentSnapshot> snapshots) {
        for (ItemComponentSnapshot snapshot : snapshots) {
            String model = snapshot.itemModel();
            if (model != null) {
                byModel.computeIfAbsent(model, ignored -> new ArrayList<>()).add(snapshot);
            } else if (snapshot.customModelData() != null) {
                byBaseWithCustomModelData.computeIfAbsent(snapshot.baseItem(), ignored -> new ArrayList<>()).add(snapshot);
            }
        }
        this.size = snapshots.size();
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * @param definition the pack's items definition
     * @param leaf       the leaf being mapped
     * @param allLeaves  every leaf of the definition, to find which threshold a value selects
     * @param baseItem   the Java base item the mapping is written under
     * @return the custom items this definition stands for under that base item
     */
    public List<ItemComponentSnapshot> matching(ItemsDefinition definition, ResolvedLeaf leaf,
                                                List<ResolvedLeaf> allLeaves, String baseItem) {
        if (size == 0) return List.of();
        if (definition.isLegacyCustomModelData()) {
            float wanted = definition.legacyCustomModelData().floatValue();
            return byBaseWithCustomModelData.getOrDefault(baseItem, List.of()).stream()
                    .filter(stack -> stack.customModelData() == wanted)
                    .toList();
        }
        if (definition.isRootVanillaItem()) {
            Double threshold = customModelDataThreshold(leaf);
            if (threshold == null) return List.of();
            List<Double> thresholds = allLeaves.stream()
                    .map(ItemComponentIndex::customModelDataThreshold)
                    .filter(java.util.Objects::nonNull)
                    .sorted()
                    .toList();
            return byBaseWithCustomModelData.getOrDefault(baseItem, List.of()).stream()
                    .filter(stack -> threshold.equals(selected(thresholds, stack.customModelData())))
                    .toList();
        }
        return byModel.getOrDefault(definition.itemIdentifier(), List.of()).stream()
                .filter(stack -> stack.baseItem().equals(baseItem))
                .toList();
    }

    /** The threshold of a leaf gated only by {@code range_dispatch custom_model_data} index 0, scale 1. */
    static Double customModelDataThreshold(ResolvedLeaf leaf) {
        if (leaf.predicates().size() != 1) return null;
        if (!(leaf.predicates().get(0) instanceof PredicateRecord.RangeDispatch range)) return null;
        if (!"custom_model_data".equals(range.property()) || range.scale() != 1.0) return null;
        JsonElement index = range.extras().get("index");
        if (index != null && (!index.isJsonPrimitive() || index.getAsInt() != 0)) return null;
        return range.threshold();
    }

    private static Double selected(List<Double> ascendingThresholds, float value) {
        Double chosen = null;
        for (Double threshold : ascendingThresholds) {
            if (threshold <= value) chosen = threshold;
        }
        return chosen;
    }
}
