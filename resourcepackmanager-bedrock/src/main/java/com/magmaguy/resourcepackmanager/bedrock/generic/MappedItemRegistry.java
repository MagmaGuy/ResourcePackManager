package com.magmaguy.resourcepackmanager.bedrock.generic;

import com.google.gson.JsonObject;
import com.magmaguy.resourcepackmanager.bedrock.BedrockLog;
import com.magmaguy.resourcepackmanager.mixer.bedrock.GeyserMappingIdentity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Accumulates Geyser definition entries across the generic Java&rarr;Bedrock pipeline.
 * One instance per {@code BedrockConversion.generate} call.
 *
 * <p>Deduplication rules:
 * <ul>
 *   <li>{@link #registerModelOnce(String)} returns true exactly once per unique
 *       {@code bedrockSafeIdentifier} &mdash; the orchestrator must write geometry/texture files
 *       only on the first call.</li>
 *   <li>{@link #addMapping(String, GeyserDefinitionEntry)} first dedupes by the tuple
 *       {@code (baseItem, bedrockIdentifier, predicateShape)} &mdash; two leaves under the same
 *       base item that resolve to the same model with the same predicate stack are skipped
 *       silently (Geyser rejects duplicate identifiers outright).</li>
 *   <li>It then dedupes by {@link GeyserMappingIdentity Geyser's conflict identity}
 *       (base item + emitted {@code model} + parsed predicate set). Geyser registers only
 *       one definition per identity and logs "both entries have the same predicates" for
 *       the rest, so emitting both would just pick a random winner at boot. Sources of such
 *       pairs: two merged packs claiming the same custom_model_data on one item with
 *       different models, duplicate thresholds/overrides inside a single pack, and
 *       dual-format packs whose modern items definition and legacy override describe the
 *       same matcher. Winner policy: a modern {@code type=definition} entry beats a
 *       {@code type=legacy} one (modern is what current Java clients render); between
 *       entries of the same type the last one wins, matching vanilla's pick among
 *       duplicate thresholds/overrides. Dropped pairs are reported via
 *       {@link #dedupNotes()} for the caller's one-line summary.</li>
 * </ul>
 *
 * <p>The {@link #safeIdentifier(String)} rule matches Rainbow's
 * {@code Rainbow.bedrockSafeIdentifier} (Rainbow.java:18-20): {@code ':'} -&gt; {@code '.'},
 * {@code '/'} -&gt; {@code '_'}. Used for filesystem paths and the {@code item_texture.json}
 * lookup key.
 */
public final class MappedItemRegistry {

    private final Set<String> writtenModels = new LinkedHashSet<>();
    private final Map<String, List<GeyserDefinitionEntry>> definitionsByBase = new LinkedHashMap<>();
    private final Set<String> emittedEntryKeys = new LinkedHashSet<>(); // baseItem|bedrockId|predicateShape
    // Geyser conflict identity -> position of the entry currently holding that identity.
    private final Map<String, EmittedEntry> entriesByIdentity = new LinkedHashMap<>();
    private final List<String> dedupNotes = new ArrayList<>();

    private record EmittedEntry(String baseItem, int index, GeyserDefinitionEntry entry) {}

    /**
     * Rainbow-style identifier sanitisation (mirrors {@code Rainbow.bedrockSafeIdentifier}):
     * {@code ':'} becomes {@code '.'}, {@code '/'} becomes {@code '_'}. Anything else is
     * preserved. Used to derive deterministic filenames and {@code item_texture.json} keys
     * from a Java/Bedrock identifier.
     */
    public static String safeIdentifier(String identifier) {
        return identifier.replace(':', '.').replace('/', '_');
    }

    public boolean registerModelOnce(String javaItemModel) {
        return writtenModels.add(safeIdentifier(javaItemModel));
    }

    public void addMapping(String baseItem, GeyserDefinitionEntry entry) {
        String key = baseItem + "|" + entry.bedrockIdentifier() + "|" + predicateShape(entry.predicates());
        if (!emittedEntryKeys.add(key)) {
            // Per-duplicate dedup notice — expected on any pack that emits the same
            // (model, base item) tuple from more than one items-definition path (e.g.
            // multiple EliteMobs tiers that all resolve to the same base sword id).
            // Demoted to debug; not actionable for operators.
            BedrockLog.debug("[BedrockConverter] Duplicate generic mapping skipped: base=" + baseItem
                    + " id=" + entry.bedrockIdentifier());
            return;
        }

        String identity = geyserConflictIdentity(baseItem, entry);
        List<GeyserDefinitionEntry> bucket =
                definitionsByBase.computeIfAbsent(baseItem, k -> new ArrayList<>());
        EmittedEntry previous = entriesByIdentity.get(identity);
        if (previous == null) {
            entriesByIdentity.put(identity, new EmittedEntry(baseItem, bucket.size(), entry));
            bucket.add(entry);
            return;
        }

        // Same Geyser conflict identity as an already-emitted entry: keep exactly one.
        // Modern beats legacy; otherwise the later entry wins (vanilla resolves duplicate
        // thresholds/overrides to the last one in file order).
        boolean keepPrevious = !previous.entry().isLegacy() && entry.isLegacy();
        GeyserDefinitionEntry kept;
        GeyserDefinitionEntry dropped;
        if (keepPrevious) {
            kept = previous.entry();
            dropped = entry;
        } else {
            kept = entry;
            dropped = previous.entry();
            bucket.set(previous.index(), entry);
            entriesByIdentity.put(identity, new EmittedEntry(baseItem, previous.index(), entry));
        }
        dedupNotes.add(baseItem + " (" + describeMatcher(kept) + "): kept "
                + kept.bedrockIdentifier() + ", dropped " + dropped.bedrockIdentifier());
    }

    public Map<String, List<GeyserDefinitionEntry>> finalDefinitions() {
        return definitionsByBase;
    }

    public int totalMappings() {
        // Count actual emitted entries; identity dedup can replace entries after their
        // (base, id, shape) key was recorded, so the key set over-counts.
        int total = 0;
        for (List<GeyserDefinitionEntry> bucket : definitionsByBase.values()) total += bucket.size();
        return total;
    }

    public int uniqueModelsWritten() {
        return writtenModels.size();
    }

    /**
     * Same-predicate pairs collapsed by {@link #addMapping}, one human-readable note per
     * pair, in emission order. The conversion entry point logs these as a single summary
     * line; without the dedup each pair would surface as a Geyser boot error instead.
     */
    public List<String> dedupNotes() {
        return Collections.unmodifiableList(dedupNotes);
    }

    private static String geyserConflictIdentity(String baseItem, GeyserDefinitionEntry entry) {
        if (entry.isLegacy()) {
            return GeyserMappingIdentity.forLegacy(baseItem, entry.legacyCustomModelData().doubleValue());
        }
        List<JsonObject> predicates = new ArrayList<>(entry.predicates().size());
        for (PredicateRecord predicate : entry.predicates()) predicates.add(predicate.toGeyserJson());
        return GeyserMappingIdentity.forModern(baseItem, entry.javaItemModel(), predicates);
    }

    private static String describeMatcher(GeyserDefinitionEntry entry) {
        if (entry.isLegacy()) {
            return "custom_model_data " + entry.legacyCustomModelData().stripTrailingZeros().toPlainString();
        }
        if (entry.predicates().isEmpty()) {
            return "model " + entry.javaItemModel() + ", no predicates";
        }
        List<String> parts = new ArrayList<>(entry.predicates().size());
        for (PredicateRecord predicate : entry.predicates()) {
            parts.add(GeyserMappingIdentity.canonicalPredicate(predicate.toGeyserJson()));
        }
        return "model " + entry.javaItemModel() + " [" + String.join(", ", parts) + "]";
    }

    /**
     * Canonical, deterministic signature of a predicate stack. Used both as part of the
     * exact-duplicate dedup key here and as the predicate component of the Geyser
     * {@code bedrock_identifier} hash (see
     * {@link com.magmaguy.resourcepackmanager.bedrock.util.BedrockShortName#forBaseMapping(String, String, String)}),
     * so identifier disambiguation and duplicate detection always agree on what
     * "the same predicate" means. Empty stack &rarr; empty string.
     */
    public static String predicateShape(List<PredicateRecord> predicates) {
        StringBuilder sb = new StringBuilder();
        for (PredicateRecord p : predicates) sb.append(p.toGeyserJson()).append(';');
        return sb.toString();
    }
}
