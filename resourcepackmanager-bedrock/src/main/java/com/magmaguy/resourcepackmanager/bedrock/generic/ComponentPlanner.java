package com.magmaguy.resourcepackmanager.bedrock.generic;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Decides which components the Geyser definitions of one (model, base item) carry, from the
 * custom items that use that model.
 *
 * <p>Usually one item uses a model and its components are copied as they are. When several
 * items share a model, each component is settled on its own:</p>
 * <ul>
 *   <li>every item gives it the same value: the value is written;</li>
 *   <li>values differ: {@code max_stack_size} takes the smallest, so Bedrock never stacks
 *       higher than the server allows; any other component is left out (the base item's
 *       behaviour, as before) and a note says so;</li>
 *   <li>some items have it and some do not: the definition is split with Geyser
 *       {@code has_component} predicates, so each stack picks the definition that matches it.
 *       The largest group keeps the definition without the extra predicates and catches every
 *       other stack. More than {@value #MAX_SPLIT_COMPONENTS} such components would need too
 *       many definitions, so they are all left out instead.</li>
 * </ul>
 */
public final class ComponentPlanner {

    static final int MAX_SPLIT_COMPONENTS = 3;
    private static final String MAX_STACK_SIZE = "minecraft:max_stack_size";

    private ComponentPlanner() {
    }

    /**
     * @param stacks         the custom items on this model and base item; empty for a model no
     *                       plugin registered
     * @param dyeTintDefault the model leaf's dye tint default, or {@code null}
     * @param allowSplit     {@code false} where Geyser takes no predicates (legacy
     *                       custom-model-data definitions); components that would split are
     *                       then left out
     * @param notes          receives one line per component left out because items disagree
     */
    public static ComponentPlan plan(List<ItemComponentSnapshot> stacks, Integer dyeTintDefault,
                                     boolean allowSplit, List<String> notes) {
        if (stacks.isEmpty()) {
            JsonObject options = new JsonObject();
            addDyeable(options, List.of(), dyeTintDefault);
            return options.isEmpty() ? ComponentPlan.NONE : ComponentPlan.single(new JsonObject(), options);
        }

        // Components whose presence differs between stacks split the definition.
        List<String> split = new ArrayList<>();
        for (String component : GeyserComponents.SUPPORTED) {
            if (stacks.stream().noneMatch(stack -> stack.changes(component))) continue;
            long present = stacks.stream().filter(stack -> stack.effective(component) != null).count();
            if (present != 0 && present != stacks.size()) split.add(component);
        }
        if (!split.isEmpty() && (!allowSplit || split.size() > MAX_SPLIT_COMPONENTS)) {
            notes.add(describe(stacks) + ": " + String.join(", ", split) + " present on only some items"
                    + (allowSplit ? "" : " of a custom-model-data mapping") + "; left out");
            List<ComponentPlan.Variant> one = List.of(variant(stacks, List.of(), Set.copyOf(split), dyeTintDefault, notes));
            return new ComponentPlan(one);
        }
        if (split.isEmpty()) {
            return new ComponentPlan(List.of(variant(stacks, List.of(), Set.of(), dyeTintDefault, notes)));
        }

        // One group per combination of the split components each stack has.
        Map<Set<String>, List<ItemComponentSnapshot>> groups = new LinkedHashMap<>();
        for (ItemComponentSnapshot stack : stacks) {
            Set<String> has = new TreeSet<>();
            for (String component : split) if (stack.effective(component) != null) has.add(component);
            groups.computeIfAbsent(has, ignored -> new ArrayList<>()).add(stack);
        }
        Set<String> fallback = groups.entrySet().stream()
                .max(Comparator.<Map.Entry<Set<String>, List<ItemComponentSnapshot>>>comparingInt(e -> e.getValue().size())
                        .thenComparing(e -> -e.getKey().size())
                        .thenComparing(e -> String.join(",", e.getKey()), Comparator.reverseOrder()))
                .orElseThrow().getKey();

        List<ComponentPlan.Variant> variants = new ArrayList<>();
        for (Map.Entry<Set<String>, List<ItemComponentSnapshot>> group : groups.entrySet()) {
            List<PredicateRecord> predicates = new ArrayList<>();
            if (!group.getKey().equals(fallback)) {
                for (String component : split) {
                    predicates.add(new PredicateRecord.Condition("has_component", group.getKey().contains(component),
                            Map.of("component", new JsonPrimitive(component))));
                }
            }
            variants.add(variant(group.getValue(), predicates, Set.of(), dyeTintDefault, notes));
        }
        // The definition without extra predicates last, as Geyser checks it last.
        variants.sort(Comparator.comparingInt(variant -> variant.extraPredicates().isEmpty() ? 1 : 0));
        return new ComponentPlan(variants);
    }

    private static ComponentPlan.Variant variant(List<ItemComponentSnapshot> stacks, List<PredicateRecord> predicates,
                                                 Set<String> leftOut, Integer dyeTintDefault, List<String> notes) {
        JsonObject components = new JsonObject();
        for (String component : GeyserComponents.SUPPORTED) {
            if (leftOut.contains(component)) continue;
            if (stacks.stream().noneMatch(stack -> stack.changes(component))) continue;
            boolean anyPresent = stacks.stream().anyMatch(stack -> stack.effective(component) != null);
            if (!anyPresent) {
                // Every stack removes a component its base item has.
                if (stacks.stream().anyMatch(stack -> stack.prototype().has(component))) {
                    components.add("!" + component, new JsonObject());
                }
                continue;
            }
            List<Optional<JsonElement>> values = stacks.stream()
                    .map(stack -> GeyserComponents.sanitize(component, stack.effective(component)))
                    .toList();
            if (values.stream().anyMatch(Optional::isEmpty)) {
                // Geyser cannot express this value for at least one stack.
                continue;
            }
            Set<JsonElement> distinct = new java.util.LinkedHashSet<>();
            values.forEach(value -> distinct.add(value.get()));
            if (distinct.size() == 1) {
                components.add(component, distinct.iterator().next());
            } else if (MAX_STACK_SIZE.equals(component)) {
                int smallest = distinct.stream().mapToInt(JsonElement::getAsInt).min().orElse(1);
                components.addProperty(component, smallest);
            } else {
                notes.add(describe(stacks) + ": " + component + " differs between items; left out");
            }
        }
        // Geyser documents attack_range as working only with a kinetic or piercing weapon.
        if (components.has("minecraft:attack_range")
                && !hasEffectively(stacks, components, "minecraft:kinetic_weapon")
                && !hasEffectively(stacks, components, "minecraft:piercing_weapon")) {
            components.remove("minecraft:attack_range");
        }

        JsonObject options = new JsonObject();
        addProtection(options, stacks);
        addDyeable(options, stacks, dyeTintDefault);
        return new ComponentPlan.Variant(predicates, components, options);
    }

    private static boolean hasEffectively(List<ItemComponentSnapshot> stacks, JsonObject components, String component) {
        return components.has(component) || stacks.stream().allMatch(stack -> stack.effective(component) != null);
    }

    /**
     * Bedrock's armor bar reads protection from the definition. Written when every stack is
     * worn in an armor slot and they agree on the armor their attributes give.
     */
    private static void addProtection(JsonObject options, List<ItemComponentSnapshot> stacks) {
        boolean changes = stacks.stream().anyMatch(stack -> stack.changes("minecraft:attribute_modifiers")
                || stack.changes("minecraft:equippable"));
        if (!changes) return;
        Set<Integer> protection = new TreeSet<>();
        for (ItemComponentSnapshot stack : stacks) {
            if (!GeyserComponents.isArmorSlot(stack.effective("minecraft:equippable"))) return;
            protection.add(GeyserComponents.protectionValue(stack.effective("minecraft:attribute_modifiers")));
        }
        if (protection.size() == 1 && protection.iterator().next() > 0) {
            options.addProperty("protection_value", protection.iterator().next());
        }
    }

    /**
     * A model whose Java leaf is tinted by dye is dyeable on Bedrock. The default colour is the
     * colour every stack is dyed when they agree, otherwise the tint's own default.
     */
    private static void addDyeable(JsonObject options, List<ItemComponentSnapshot> stacks, Integer dyeTintDefault) {
        if (dyeTintDefault == null) return;
        Integer colour = dyeTintDefault;
        Set<Integer> dyed = new TreeSet<>();
        for (ItemComponentSnapshot stack : stacks) {
            Integer rgb = dyedColour(stack.effective("minecraft:dyed_color"));
            if (rgb == null) {
                dyed.clear();
                break;
            }
            dyed.add(rgb);
        }
        if (dyed.size() == 1) colour = dyed.iterator().next();
        JsonObject dyeable = new JsonObject();
        dyeable.addProperty("default_color", String.format("#%06X", colour & 0xFFFFFF));
        options.add("dyeable", dyeable);
    }

    /** 1.21.4 writes {@code {"rgb": n}}, 1.21.5 and newer a bare number. */
    private static Integer dyedColour(JsonElement dyed) {
        if (dyed == null) return null;
        try {
            if (dyed.isJsonPrimitive()) return dyed.getAsInt() & 0xFFFFFF;
            if (dyed.isJsonObject() && dyed.getAsJsonObject().has("rgb")) {
                return dyed.getAsJsonObject().get("rgb").getAsInt() & 0xFFFFFF;
            }
        } catch (RuntimeException unreadable) {
            return null;
        }
        return null;
    }

    private static String describe(List<ItemComponentSnapshot> stacks) {
        List<String> ids = stacks.stream().map(ItemComponentSnapshot::itemId).filter(Objects::nonNull).sorted().toList();
        String shown = ids.size() > 4 ? String.join(", ", ids.subList(0, 4)) + ", ..." : String.join(", ", ids);
        return stacks.get(0).source() + " items [" + shown + "] on " + stacks.get(0).baseItem();
    }
}
