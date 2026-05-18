package com.magmaguy.resourcepackmanager.bedrock.generic;

import java.util.List;

/**
 * One Geyser custom-item-v2 definition entry produced by the generic pipeline.
 * Mirrors the field-order convention of {@code FmmGeyserMappingBuilder}: an entry's
 * serialised JSON emits keys in the order {@code type, bedrock_identifier, display_name,
 * predicate, bedrock_options, components, model}.
 *
 * @param bedrockIdentifier Bedrock-side identifier, e.g. {@code "elitemobs:gear/bronze_sword"}.
 *                          Per Rainbow's {@code BedrockItemMapper.java:256-261}, when the source
 *                          model namespace is non-vanilla, the namespaced identifier is forwarded
 *                          to Bedrock unchanged (the {@code /} stays in the path).
 * @param javaItemModel     The Java item_model component value, e.g. {@code "elitemobs:gear/bronze_sword"}.
 * @param predicates        Ordered list of predicates accumulated from the model tree walk.
 *                          Emitted as an array on the Geyser entry.
 * @param iconKey           Key into {@code item_texture.json}. Per Rainbow's
 *                          {@code GeyserBaseDefinition.textureName} (defaults to
 *                          {@code bedrockSafeIdentifier(bedrockIdentifier)}), this is the
 *                          Rainbow-safe form of the bedrock identifier: {@code ':'} -> {@code '.'},
 *                          {@code '/'} -> {@code '_'}.
 * @param handheld          Drives {@code bedrock_options.display_handheld}; true iff the
 *                          resolved model's parent chain hits {@code item/handheld*}.
 */
public record GeyserDefinitionEntry(
        String bedrockIdentifier,
        String javaItemModel,
        List<PredicateRecord> predicates,
        String iconKey,
        boolean handheld
) {
    public GeyserDefinitionEntry {
        predicates = List.copyOf(predicates);
    }
}
