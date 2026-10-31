package com.magmaguy.resourcepackmanager.thirdparty;

import com.magmaguy.resourcepackmanager.config.compatibleplugins.CompatiblePluginConfigFields;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Integrations whose plugin is installed but has not written its resource pack yet.
 *
 * <p>Some plugins only write their pack after startup: CraftEngine builds it when its pack
 * workflow runs (/ce reload all). Such an entry waits here instead of being dropped, and the
 * change watchdog registers it as soon as one of its paths exists. Waiting entries are not
 * monitored packs, so they never hold back the mix of the packs that are present.</p>
 */
final class PendingPackSources {
    private final Map<String, CompatiblePluginConfigFields> waiting = new ConcurrentHashMap<>();

    void add(CompatiblePluginConfigFields fields) {
        waiting.put(fields.getPluginName().toLowerCase(Locale.ROOT), fields);
    }

    /**
     * Removes and returns the entries whose local or additional path now exists.
     *
     * @param pathExists tests a plugins-relative path from the entry
     */
    List<CompatiblePluginConfigFields> takeArrived(Predicate<String> pathExists) {
        List<CompatiblePluginConfigFields> arrived = new ArrayList<>();
        for (Map.Entry<String, CompatiblePluginConfigFields> entry : waiting.entrySet()) {
            CompatiblePluginConfigFields fields = entry.getValue();
            if (!exists(fields.getLocalPath(), pathExists) && !exists(fields.getAdditionalLocalPath(), pathExists)) continue;
            if (waiting.remove(entry.getKey(), fields)) arrived.add(fields);
        }
        arrived.sort(Comparator.comparing(CompatiblePluginConfigFields::getPluginName, String.CASE_INSENSITIVE_ORDER));
        return arrived;
    }

    /** The waiting entries, sorted by plugin name. */
    List<CompatiblePluginConfigFields> snapshot() {
        List<CompatiblePluginConfigFields> entries = new ArrayList<>(waiting.values());
        entries.sort(Comparator.comparing(CompatiblePluginConfigFields::getPluginName, String.CASE_INSENSITIVE_ORDER));
        return entries;
    }

    boolean isEmpty() {
        return waiting.isEmpty();
    }

    void clear() {
        waiting.clear();
    }

    private static boolean exists(String path, Predicate<String> pathExists) {
        return path != null && !path.isBlank() && pathExists.test(path);
    }
}
