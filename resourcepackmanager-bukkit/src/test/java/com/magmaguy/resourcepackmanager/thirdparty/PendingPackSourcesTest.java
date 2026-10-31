package com.magmaguy.resourcepackmanager.thirdparty;

import com.magmaguy.resourcepackmanager.config.compatibleplugins.CompatiblePluginConfigFields;
import com.magmaguy.resourcepackmanager.config.compatibleplugins.premade.CraftEngine;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CraftEngine writes its pack only when /ce reload all runs, after RSPM has started. The
 * entry used to be dropped with a warning until the next restart (RSPM#20); now it waits
 * and is handed back once, when its file appears.
 */
final class PendingPackSourcesTest {

    private final Set<String> existingPaths = new HashSet<>();

    @Test
    void anEntryWaitsUntilItsPackAppearsAndIsRegisteredOnce() {
        PendingPackSources pending = new PendingPackSources();
        CraftEngine craftEngine = new CraftEngine();
        pending.add(craftEngine);

        assertEquals(List.of(), pending.takeArrived(existingPaths::contains));
        assertEquals(List.of(craftEngine), pending.snapshot(), "Still waiting while the zip is missing");

        existingPaths.add("CraftEngine/generated/resource_pack.zip");
        assertEquals(List.of(craftEngine), pending.takeArrived(existingPaths::contains));
        assertEquals(List.of(), pending.takeArrived(existingPaths::contains), "Handed back only once");
        assertTrue(pending.isEmpty());
    }

    @Test
    void anAdditionalPathAlsoCountsAndOnlyArrivedEntriesLeave() {
        PendingPackSources pending = new PendingPackSources();
        CompatiblePluginConfigFields shared = entry("Shared", "Shared/pack.zip", "Shared/folder");
        CompatiblePluginConfigFields later = entry("Later", "Later/pack.zip", null);
        pending.add(later);
        pending.add(shared);

        existingPaths.add("Shared/folder");
        assertEquals(List.of(shared), pending.takeArrived(existingPaths::contains));
        assertEquals(List.of(later), pending.snapshot());
    }

    @Test
    void reAddingAPluginReplacesItsEntry() {
        PendingPackSources pending = new PendingPackSources();
        pending.add(entry("CraftEngine", "old/path.zip", null));
        CompatiblePluginConfigFields current = entry("craftengine", "CraftEngine/generated/resource_pack.zip", null);
        pending.add(current);

        assertEquals(List.of(current), pending.snapshot());
        pending.clear();
        assertTrue(pending.isEmpty());
    }

    private static CompatiblePluginConfigFields entry(String pluginName, String localPath, String additionalPath) {
        CompatiblePluginConfigFields fields = new CompatiblePluginConfigFields(pluginName.toLowerCase(), true);
        fields.setPluginName(pluginName);
        fields.setLocalPath(localPath);
        fields.setAdditionalLocalPath(additionalPath);
        return fields;
    }
}
