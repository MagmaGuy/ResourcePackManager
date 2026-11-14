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
    // Last threshold sort, keyed by the identity of the leaf list it came from. The conversion
    // calls matching() from one thread, one definition at a time.
    private List<ResolvedLeaf> cachedThresholdLeaves;
    private double[] cachedThresholds;

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
            double[] thresholds = sortedThresholds(allLeaves);
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

    /**
     * The definition's thresholds in ascending {@link Double#compareTo} order. Every leaf and
     * base item of one definition passes the same leaf list, so the last sort is kept and reused
     * while that list is passed again.
     */
    private double[] sortedThresholds(List<ResolvedLeaf> allLeaves) {
        if (allLeaves == cachedThresholdLeaves) return cachedThresholds;
        double[] sorted = allLeaves.stream()
                .map(ItemComponentIndex::customModelDataThreshold)
                .filter(java.util.Objects::nonNull)
                .sorted()
                .mapToDouble(Double::doubleValue)
                .toArray();
        cachedThresholdLeaves = allLeaves;
        cachedThresholds = sorted;
        return sorted;
    }

    /**
     * The highest threshold not above {@code value}: the last entry in ascending order for which
     * {@code threshold <= value} holds. That test is monotone over the sorted array (NaN sorts
     * last and never passes; -0.0 and 0.0 pass or fail together), so a binary search finds the
     * same entry a linear scan would.
     */
    private static Double selected(double[] ascendingThresholds, float value) {
        int low = 0;
        int high = ascendingThresholds.length - 1;
        int chosen = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (ascendingThresholds[mid] <= value) {
                chosen = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return chosen < 0 ? null : ascendingThresholds[chosen];
    }
}
