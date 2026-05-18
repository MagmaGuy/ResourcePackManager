package com.magmaguy.resourcepackmanager.bedrock.util;

/**
 * Tiny helpers for splitting Java Edition namespaced identifiers.
 */
public class BedrockNaming {

    /**
     * Extracts namespace from a namespaced ID like "elitemobs:gear/bronze_sword".
     * Returns "elitemobs". Defaults to "minecraft" when no colon is present.
     */
    public static String extractNamespace(String namespacedId) {
        if (namespacedId == null || namespacedId.isEmpty()) return "";
        int colonIndex = namespacedId.indexOf(':');
        if (colonIndex < 0) return "minecraft";
        return namespacedId.substring(0, colonIndex);
    }

    /**
     * Extracts path from a namespaced ID like "elitemobs:gear/bronze_sword".
     * Returns "gear/bronze_sword". Returns the raw input when no colon is present.
     */
    public static String extractPath(String namespacedId) {
        if (namespacedId == null || namespacedId.isEmpty()) return "";
        int colonIndex = namespacedId.indexOf(':');
        if (colonIndex < 0) return namespacedId;
        return namespacedId.substring(colonIndex + 1);
    }
}
