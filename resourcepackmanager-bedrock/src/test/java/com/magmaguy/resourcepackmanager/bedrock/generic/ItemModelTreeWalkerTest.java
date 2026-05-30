package com.magmaguy.resourcepackmanager.bedrock.generic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ItemModelTreeWalker}'s type-dispatch.
 *
 * <p>Mojang parses the items-definition {@code type} field as a ResourceLocation, so an
 * unnamespaced value like {@code "model"} is equivalent to {@code "minecraft:model"} and
 * renders correctly on Java. Nexo emits the unnamespaced form; the walker must dispatch it
 * identically to the fully-qualified form rather than dropping the item.
 */
class ItemModelTreeWalkerTest {

    private static ItemsDefinition def(String namespace, String relPath, String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return new ItemsDefinition(namespace, relPath, null, root);
    }

    @Test
    void bareModelType_atRoot_producesUnconditionalLeaf() {
        // Nexo writes the leaf type without the minecraft: prefix (e.g. nexo:pixiestudios_questicon1).
        ItemsDefinition def = def("nexo", "pixiestudios_questicon1", """
                {
                  "model": {
                    "type": "model",
                    "model": "nexo:item/pixiestudios_questicon1"
                  }
                }
                """);

        List<ResolvedLeaf> leaves = ItemModelTreeWalker.walk(def, null);

        assertEquals(1, leaves.size(), "bare 'model' type should resolve to one leaf");
        assertEquals("nexo:item/pixiestudios_questicon1", leaves.get(0).modelRef());
        assertTrue(leaves.get(0).predicates().isEmpty(), "root leaf has no predicates");
    }

    @Test
    void prefixedModelType_atRoot_stillProducesLeaf() {
        // Regression guard: the canonical minecraft:-prefixed form must keep working.
        ItemsDefinition def = def("elitemobs", "gear/bronze_sword", """
                {
                  "model": {
                    "type": "minecraft:model",
                    "model": "elitemobs:gear/bronze_sword"
                  }
                }
                """);

        List<ResolvedLeaf> leaves = ItemModelTreeWalker.walk(def, null);

        assertEquals(1, leaves.size());
        assertEquals("elitemobs:gear/bronze_sword", leaves.get(0).modelRef());
    }

    @Test
    void bareConditionType_isDispatchedLikePrefixed() {
        // Proves the namespace-tolerant dispatch generalises beyond the leaf type:
        // a bare 'condition' branches into both subtrees just like 'minecraft:condition'.
        ItemsDefinition def = def("nexo", "broken_tool", """
                {
                  "model": {
                    "type": "condition",
                    "property": "broken",
                    "on_true":  { "type": "model", "model": "nexo:item/broken" },
                    "on_false": { "type": "minecraft:model", "model": "nexo:item/intact" }
                  }
                }
                """);

        List<ResolvedLeaf> leaves = ItemModelTreeWalker.walk(def, null);

        assertEquals(2, leaves.size(), "condition should produce on_true + on_false leaves");
        assertTrue(leaves.stream().anyMatch(l -> l.modelRef().equals("nexo:item/broken")));
        assertTrue(leaves.stream().anyMatch(l -> l.modelRef().equals("nexo:item/intact")));
    }
}
