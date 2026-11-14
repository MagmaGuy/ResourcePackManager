package com.magmaguy.resourcepackmanager.bedrock.generic;

import com.google.gson.JsonObject;

import java.math.BigDecimal;
import java.util.List;

/**
 * One Geyser custom-item-v2 entry produced by the generic pipeline. Modern item-model
 * mappings use {@code type=definition} plus {@code model}; pre-1.21.4 custom-model-data
 * mappings use {@code type=legacy} plus {@code custom_model_data}.
 *
 * @param bedrockIdentifier Bedrock-side identifier, e.g. {@code "elitemobs:gear/bronze_sword"}.
 *                          Per Rainbow's {@code BedrockItemMapper.java:256-261}, when the source
 *                          model namespace is non-vanilla, the namespaced identifier is forwarded
 *                          to Bedrock unchanged (the {@code /} stays in the path).
 * @param javaItemModel     The Java item_model component value, e.g. {@code "elitemobs:gear/bronze_sword"}.
 * @param predicates        Ordered list of predicates accumulated from the model tree walk.
 *                          Emitted for modern entries. A legacy entry retains its synthetic
 *                          source predicate only for deterministic identity/deduplication;
 *                          {@code custom_model_data} is its Geyser matcher.
 * @param iconKey           Key into {@code item_texture.json}. Per Rainbow's
 *                          {@code GeyserBaseDefinition.textureName} (defaults to
 *                          {@code bedrockSafeIdentifier(bedrockIdentifier)}), this is the
 *                          Rainbow-safe form of the bedrock identifier: {@code ':'} -> {@code '.'},
 *                          {@code '/'} -> {@code '_'}.
 * @param handheld          Drives {@code bedrock_options.display_handheld}; true iff the
 *                          resolved model's parent chain hits {@code item/handheld*}.
 * @param legacyCustomModelData exact pre-1.21.4 custom-model-data value, or {@code null}
 *                              for a modern item-model definition
 * @param components            Geyser {@code components} the custom item changes against its
 *                              base item ({@link ComponentPlanner}); empty writes none
 * @param extraBedrockOptions   {@code bedrock_options} fields read from the custom item, such
 *                              as {@code protection_value} and {@code dyeable}
 */
public record GeyserDefinitionEntry(
        String bedrockIdentifier,
        String javaItemModel,
        List<PredicateRecord> predicates,
        String iconKey,
        boolean handheld,
        BigDecimal legacyCustomModelData,
        JsonObject components,
        JsonObject extraBedrockOptions
) {
    public GeyserDefinitionEntry {
        predicates = predicates == null ? List.of() : List.copyOf(predicates);
        components = components == null ? new JsonObject() : components.deepCopy();
        extraBedrockOptions = extraBedrockOptions == null ? new JsonObject() : extraBedrockOptions.deepCopy();
        boolean hasModernModel = javaItemModel != null && !javaItemModel.isBlank();
        if (hasModernModel == (legacyCustomModelData != null)) {
            throw new IllegalArgumentException(
                    "A Geyser mapping must declare exactly one of model or custom_model_data");
        }
    }

    public static GeyserDefinitionEntry definition(String bedrockIdentifier,
                                                   String javaItemModel,
                                                   List<PredicateRecord> predicates,
                                                   String iconKey,
                                                   boolean handheld) {
        return new GeyserDefinitionEntry(bedrockIdentifier, javaItemModel, predicates,
                iconKey, handheld, null, null, null);
    }

    public static GeyserDefinitionEntry legacy(String bedrockIdentifier,
                                               BigDecimal customModelData,
                                               List<PredicateRecord> sourcePredicates,
                                               String iconKey,
                                               boolean handheld) {
        return new GeyserDefinitionEntry(bedrockIdentifier, null, sourcePredicates,
                iconKey, handheld, customModelData, null, null);
    }

    /** This entry carrying a custom item's components and extra bedrock options. */
    public GeyserDefinitionEntry withItemData(JsonObject itemComponents, JsonObject itemBedrockOptions) {
        return new GeyserDefinitionEntry(bedrockIdentifier, javaItemModel, predicates, iconKey, handheld,
                legacyCustomModelData, itemComponents, itemBedrockOptions);
    }

    public boolean isLegacy() {
        return legacyCustomModelData != null;
    }
}
