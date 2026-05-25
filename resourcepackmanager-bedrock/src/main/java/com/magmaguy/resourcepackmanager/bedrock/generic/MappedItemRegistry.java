package com.magmaguy.resourcepackmanager.bedrock.generic;

import com.magmaguy.resourcepackmanager.bedrock.BedrockLog;

import java.util.ArrayList;
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
 *   <li>{@link #addMapping(String, GeyserDefinitionEntry)} dedupes by the tuple
 *       {@code (bedrockIdentifier, predicateShape)} &mdash; two leaves under the same base item
 *       that resolve to the same model with the same predicate stack are skipped (warned).</li>
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
            BedrockLog.warn("[BedrockConverter] Duplicate generic mapping skipped: base=" + baseItem
                    + " id=" + entry.bedrockIdentifier());
            return;
        }
        definitionsByBase.computeIfAbsent(baseItem, k -> new ArrayList<>()).add(entry);
    }

    public Map<String, List<GeyserDefinitionEntry>> finalDefinitions() {
        return definitionsByBase;
    }

    public int totalMappings() {
        return emittedEntryKeys.size();
    }

    public int uniqueModelsWritten() {
        return writtenModels.size();
    }

    private static String predicateShape(List<PredicateRecord> predicates) {
        StringBuilder sb = new StringBuilder();
        for (PredicateRecord p : predicates) sb.append(p.toGeyserJson()).append(';');
        return sb.toString();
    }
}
