package com.magmaguy.resourcepackmanager.bedrock.generic;

import com.google.gson.JsonObject;

/**
 * Result of walking a Java item model's parent chain in {@link AssetResolver#resolveModel(String)}.
 *
 * <p>Mirrors the flat-builtin / handheld detection rules used by Rainbow's
 * BedrockGeometryContext.java:22-24, 40, which feed into the Bedrock attachable's
 * {@code bedrock_options.display_handheld} flag and the 2D-vs-3D branching at conversion time.
 *
 * @param identifier        the model identifier originally requested
 * @param rootParent        the deepest parent in the chain (or null if the model has no parent at all)
 * @param mergedJson        merged JSON of the entire parent chain — child keys override parent's
 * @param isFlatBuiltin     true if the root parent is one of the vanilla flat-icon parents
 *                          ({@code minecraft:item/generated} or {@code minecraft:builtin/generated})
 * @param isHandheldVariant true if any parent in the chain is a vanilla handheld variant
 *                          ({@code minecraft:item/handheld}, {@code _rod}, or {@code _mace})
 */
public record ResolvedModel(
        String identifier,
        String rootParent,
        JsonObject mergedJson,
        boolean isFlatBuiltin,
        boolean isHandheldVariant
) {}
