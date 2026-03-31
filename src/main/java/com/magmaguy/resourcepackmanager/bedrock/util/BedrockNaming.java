package com.magmaguy.resourcepackmanager.bedrock.util;

/**
 * Converts Java Edition resource identifiers to Bedrock/Geyser naming conventions.
 */
public class BedrockNaming {

    /**
     * Converts a Java texture reference to a Geyser item_texture.json icon key.
     * "elitemobs:items/bronzesword" -> "elitemobs.items.bronzesword"
     * Colons and slashes both become periods.
     */
    public static String toIconKey(String javaTextureRef) {
        if (javaTextureRef == null || javaTextureRef.isEmpty()) return "";
        return javaTextureRef.replace(":", ".").replace("/", ".");
    }

    /**
     * Converts a Java model reference to a Bedrock identifier for Geyser mappings.
     * "elitemobs:gear/bronze_sword" -> "elitemobs:bronze_sword"
     * Keeps the namespace, uses only the final path segment as the name.
     */
    public static String toBedrockIdentifier(String namespace, String itemPath) {
        if (namespace == null || namespace.isEmpty()) return "";
        if (itemPath == null || itemPath.isEmpty()) return namespace + ":";
        String finalSegment = getFinalSegment(itemPath);
        return namespace + ":" + finalSegment;
    }

    /**
     * Converts a Java texture reference to a Bedrock texture file path.
     * "elitemobs:items/bronzesword" -> "textures/items/elitemobs/items/bronzesword"
     * No file extension in the returned path.
     */
    public static String toBedrockTexturePath(String javaTextureRef) {
        if (javaTextureRef == null || javaTextureRef.isEmpty()) return "";
        String namespace = extractNamespace(javaTextureRef);
        String path = extractPath(javaTextureRef);
        return "textures/items/" + namespace + "/" + path;
    }

    /**
     * Extracts namespace from a namespaced ID like "elitemobs:gear/bronze_sword".
     * Returns "elitemobs".
     */
    public static String extractNamespace(String namespacedId) {
        if (namespacedId == null || namespacedId.isEmpty()) return "";
        int colonIndex = namespacedId.indexOf(':');
        if (colonIndex < 0) return "minecraft";
        return namespacedId.substring(0, colonIndex);
    }

    /**
     * Extracts path from a namespaced ID like "elitemobs:gear/bronze_sword".
     * Returns "gear/bronze_sword".
     */
    public static String extractPath(String namespacedId) {
        if (namespacedId == null || namespacedId.isEmpty()) return "";
        int colonIndex = namespacedId.indexOf(':');
        if (colonIndex < 0) return namespacedId;
        return namespacedId.substring(colonIndex + 1);
    }

    /**
     * Gets the final segment of a path. "gear/bronze_sword" -> "bronze_sword"
     */
    public static String getFinalSegment(String path) {
        if (path == null || path.isEmpty()) return "";
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash < 0) return path;
        return path.substring(lastSlash + 1);
    }
}
