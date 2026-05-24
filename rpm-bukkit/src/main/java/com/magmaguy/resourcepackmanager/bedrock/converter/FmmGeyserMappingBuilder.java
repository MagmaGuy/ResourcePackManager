package com.magmaguy.resourcepackmanager.bedrock.converter;

/**
 * Container for the FMM-side {@link BoneMapping} record consumed by
 * {@link com.magmaguy.resourcepackmanager.bedrock.generic.GenericGeyserMappingBuilder}.
 *
 * <p>The old {@code generate(...)} writer that produced a standalone FMM-only Geyser
 * mappings file was removed once the unified merge writer in
 * {@code GenericGeyserMappingBuilder} took over emission. Only the record itself remains
 * as the shared data carrier for FMM-bone mappings.
 */
public class FmmGeyserMappingBuilder {

    /** Data for a single bone's Geyser mapping. */
    public record BoneMapping(
            String itemModelKey,
            String bedrockIdentifier,
            String displayName,
            String iconKey
    ) {}
}
