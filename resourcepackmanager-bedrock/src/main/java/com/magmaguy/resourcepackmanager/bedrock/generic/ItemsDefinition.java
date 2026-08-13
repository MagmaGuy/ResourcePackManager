package com.magmaguy.resourcepackmanager.bedrock.generic;

import com.google.gson.JsonObject;

import java.io.File;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * One parsed Java 1.21.4+ items definition file (e.g. assets/elitemobs/items/gear/bronze_sword.json).
 *
 * @param namespace    the asset namespace (e.g. "elitemobs")
 * @param itemsRelPath the relative path within items/ without extension (e.g. "gear/bronze_sword")
 * @param file         the actual file on disk
 * @param root         parsed JSON contents
 * @param explicitBaseItems exact base Java item ids when the source format declares them
 * @param legacyCustomModelData the exact pre-1.21.4 custom-model-data value, or {@code null}
 *                              for a modern item-model definition
 */
public record ItemsDefinition(
        String namespace,
        String itemsRelPath,
        File file,
        JsonObject root,
        List<String> explicitBaseItems,
        BigDecimal legacyCustomModelData
) {
    public ItemsDefinition(String namespace, String itemsRelPath, File file, JsonObject root) {
        this(namespace, itemsRelPath, file, root, List.of(), null);
    }

    public ItemsDefinition(String namespace, String itemsRelPath, File file, JsonObject root,
                           List<String> explicitBaseItems) {
        this(namespace, itemsRelPath, file, root, explicitBaseItems, null);
    }

    public static ItemsDefinition legacyCustomModelData(String namespace,
                                                        String itemsRelPath,
                                                        File file,
                                                        JsonObject root,
                                                        String baseItem,
                                                        BigDecimal customModelData) {
        return new ItemsDefinition(namespace, itemsRelPath, file, root,
                List.of(baseItem), Objects.requireNonNull(customModelData, "customModelData"));
    }

    public ItemsDefinition {
        explicitBaseItems = explicitBaseItems == null ? List.of() : List.copyOf(explicitBaseItems);
        if (legacyCustomModelData != null && explicitBaseItems.isEmpty()) {
            throw new IllegalArgumentException(
                    "Legacy custom-model-data definitions require an explicit Java base item");
        }
    }

    /**
     * The fully-qualified Java item-model identifier this definition declares.
     * Used as the Geyser mapping's {@code model} field, and to derive Bedrock identifiers.
     */
    public String itemIdentifier() {
        return namespace + ":" + itemsRelPath;
    }

    public boolean hasExplicitBaseItems() {
        return !explicitBaseItems.isEmpty();
    }

    public boolean isLegacyCustomModelData() {
        return legacyCustomModelData != null;
    }
}
