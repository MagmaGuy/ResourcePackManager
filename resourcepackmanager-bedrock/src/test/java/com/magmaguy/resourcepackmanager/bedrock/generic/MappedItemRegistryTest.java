package com.magmaguy.resourcepackmanager.bedrock.generic;

import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces the conversion-side source of Geyser's boot error "conflicts with custom
 * item definition (...): both entries have the same predicates": two leaves that resolve
 * to different source models (hence different bedrock identifiers) but describe the same
 * (item, model, predicates) matcher — e.g. duplicate custom_model_data thresholds inside
 * one merged items file, or a dual-format pack's modern definition plus legacy override.
 */
class MappedItemRegistryTest {

    private static final String BASE = "minecraft:paper";

    private static List<PredicateRecord> cmdPredicate(double threshold) {
        return List.of(new PredicateRecord.RangeDispatch(
                "custom_model_data", threshold, 1.0, Map.of("index", new JsonPrimitive(0))));
    }

    private static GeyserDefinitionEntry modern(String bedrockId, double cmd) {
        return GeyserDefinitionEntry.definition(bedrockId, BASE, cmdPredicate(cmd), "icon", false);
    }

    private static GeyserDefinitionEntry legacy(String bedrockId, double cmd) {
        return GeyserDefinitionEntry.legacy(bedrockId, BigDecimal.valueOf(cmd),
                cmdPredicate(cmd), "icon", false);
    }

    @Test
    void samePredicatePairKeepsOnlyTheLastEntry() {
        MappedItemRegistry registry = new MappedItemRegistry();
        registry.addMapping(BASE, modern("r:aaaaaaaa", 101));
        registry.addMapping(BASE, modern("r:bbbbbbbb", 101));

        List<GeyserDefinitionEntry> entries = registry.finalDefinitions().get(BASE);
        assertEquals(1, entries.size());
        // Vanilla resolves duplicate thresholds/overrides to the last one in file order,
        // so the later emission wins.
        assertEquals("r:bbbbbbbb", entries.get(0).bedrockIdentifier());
        assertEquals(1, registry.totalMappings());
        assertEquals(1, registry.dedupNotes().size());
        String note = registry.dedupNotes().get(0);
        assertTrue(note.contains("r:bbbbbbbb") && note.contains("r:aaaaaaaa"),
                "note should name both identifiers: " + note);
    }

    @Test
    void modernDefinitionBeatsLegacyRegardlessOfOrder() {
        // Dual-format packs (modern items file + legacy overrides) describe one matcher
        // twice; the modern entry is what current Java clients render, so it wins.
        MappedItemRegistry modernFirst = new MappedItemRegistry();
        modernFirst.addMapping(BASE, modern("r:aaaaaaaa", 101));
        modernFirst.addMapping(BASE, legacy("r:bbbbbbbb", 101));
        assertEquals(List.of("r:aaaaaaaa"),
                modernFirst.finalDefinitions().get(BASE).stream()
                        .map(GeyserDefinitionEntry::bedrockIdentifier).toList());

        MappedItemRegistry legacyFirst = new MappedItemRegistry();
        legacyFirst.addMapping(BASE, legacy("r:bbbbbbbb", 101));
        legacyFirst.addMapping(BASE, modern("r:aaaaaaaa", 101));
        assertEquals(List.of("r:aaaaaaaa"),
                legacyFirst.finalDefinitions().get(BASE).stream()
                        .map(GeyserDefinitionEntry::bedrockIdentifier).toList());
    }

    @Test
    void distinctMatchersCoexist() {
        MappedItemRegistry registry = new MappedItemRegistry();
        registry.addMapping(BASE, modern("r:aaaaaaaa", 101));
        registry.addMapping(BASE, modern("r:bbbbbbbb", 102));
        registry.addMapping("minecraft:stick", modern("r:cccccccc", 101));

        assertEquals(3, registry.totalMappings());
        assertTrue(registry.dedupNotes().isEmpty());
    }

    @Test
    void exactDuplicateEmissionIsSkippedSilently() {
        // Same identifier + same shape = the same (model, base, predicates) tuple emitted
        // twice (e.g. from two items-definition paths); skipped without a dedup note.
        MappedItemRegistry registry = new MappedItemRegistry();
        registry.addMapping(BASE, modern("r:aaaaaaaa", 101));
        registry.addMapping(BASE, modern("r:aaaaaaaa", 101));

        assertEquals(1, registry.totalMappings());
        assertTrue(registry.dedupNotes().isEmpty());
    }
}
