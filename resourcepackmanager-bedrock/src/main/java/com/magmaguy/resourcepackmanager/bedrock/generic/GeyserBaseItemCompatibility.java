package com.magmaguy.resourcepackmanager.bedrock.generic;

import java.util.Set;

/**
 * Guards custom-item bases that Geyser 2.11 cannot currently override without
 * producing an invalid Bedrock {@code minecraft:block_placer} component.
 *
 * <p>Geyser derives a vanilla block item's custom-item block placer from the
 * Bedrock <em>item</em> identifier. That is normally correct, but the Bedrock
 * item and block registries use different identifiers for banners and redstone:
 * {@code minecraft:banner} versus the standing/wall banner blocks, and
 * {@code minecraft:redstone} versus {@code minecraft:redstone_wire}. Bedrock
 * therefore reports that the derived block cannot be found in its registry.
 *
 * <p>Geyser's v2 JSON mapping format does not expose a way for a vanilla-item
 * override to suppress or correct the derived Geyser-only block-placer
 * component. Until that upstream limitation changes, omitting these definitions
 * is safer than publishing a client-invalid item registry. The affected Java
 * items continue to work and render with their vanilla icon on Bedrock.
 */
public final class GeyserBaseItemCompatibility {

    private static final Set<String> INVALID_DERIVED_BLOCK_PLACER_BASES = Set.of(
            "minecraft:black_banner",
            "minecraft:blue_banner",
            "minecraft:brown_banner",
            "minecraft:cyan_banner",
            "minecraft:gray_banner",
            "minecraft:green_banner",
            "minecraft:light_blue_banner",
            "minecraft:light_gray_banner",
            "minecraft:lime_banner",
            "minecraft:magenta_banner",
            "minecraft:orange_banner",
            "minecraft:pink_banner",
            "minecraft:purple_banner",
            "minecraft:red_banner",
            "minecraft:redstone",
            "minecraft:white_banner",
            "minecraft:yellow_banner"
    );

    private GeyserBaseItemCompatibility() {
    }

    public static boolean supportsCustomItemOverride(String baseItem) {
        return baseItem != null && !INVALID_DERIVED_BLOCK_PLACER_BASES.contains(baseItem);
    }
}
