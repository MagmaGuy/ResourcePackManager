package com.magmaguy.resourcepackmanager.bedrock.generic;

import com.google.gson.JsonObject;

import java.io.File;
import java.util.List;

/**
 * One parsed Java 1.21.4+ items definition file (e.g. assets/elitemobs/items/gear/bronze_sword.json).
 *
 * @param namespace    the asset namespace (e.g. "elitemobs")
 * @param itemsRelPath the relative path within items/ without extension (e.g. "gear/bronze_sword")
 * @param file         the actual file on disk
 * @param root         parsed JSON contents
 * @param explicitBaseItems exact base Java item ids when the source format declares them
 */
public record ItemsDefinition(
        String namespace,
        String itemsRelPath,
        File file,
        JsonObject root,
        List<String> explicitBaseItems
) {
    public ItemsDefinition(String namespace, String itemsRelPath, File file, JsonObject root) {
        this(namespace, itemsRelPath, file, root, List.of());
    }

    public ItemsDefinition {
        explicitBaseItems = explicitBaseItems == null ? List.of() : List.copyOf(explicitBaseItems);
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
}
