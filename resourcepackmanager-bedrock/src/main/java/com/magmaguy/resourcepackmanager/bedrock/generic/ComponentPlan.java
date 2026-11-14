package com.magmaguy.resourcepackmanager.bedrock.generic;

import com.google.gson.JsonObject;

import java.util.List;

/**
 * The Geyser definitions one model leaf gets under one base item: usually a single variant,
 * several when the custom items on the model differ in which components they have.
 *
 * @param variants one Geyser definition each; the variant without extra predicates comes last
 */
public record ComponentPlan(List<Variant> variants) {

    /** No custom item data: one definition carrying nothing beyond what the pack gives. */
    public static final ComponentPlan NONE = single(new JsonObject(), new JsonObject());

    public ComponentPlan {
        variants = List.copyOf(variants);
        if (variants.isEmpty()) throw new IllegalArgumentException("A plan needs at least one variant");
    }

    static ComponentPlan single(JsonObject components, JsonObject bedrockOptions) {
        return new ComponentPlan(List.of(new Variant(List.of(), components, bedrockOptions)));
    }

    /**
     * @param extraPredicates {@code has_component} predicates added to the leaf's own
     * @param components      the Geyser {@code components} object; empty writes none
     * @param bedrockOptions  extra {@code bedrock_options} fields, such as
     *                        {@code protection_value} and {@code dyeable}
     */
    public record Variant(List<PredicateRecord> extraPredicates, JsonObject components, JsonObject bedrockOptions) {
        public Variant {
            extraPredicates = List.copyOf(extraPredicates);
            components = components == null ? new JsonObject() : components.deepCopy();
            bedrockOptions = bedrockOptions == null ? new JsonObject() : bedrockOptions.deepCopy();
        }
    }
}
