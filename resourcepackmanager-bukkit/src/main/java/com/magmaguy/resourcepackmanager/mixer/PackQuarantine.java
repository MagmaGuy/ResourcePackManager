package com.magmaguy.resourcepackmanager.mixer;

import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.utils.RSPLogger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounds staging retries for a single unusable pack.
 *
 * <p>A pack that cannot be staged fails identically on every attempt, and the
 * stability watcher re-arms after each failed mix. Without a bound, one
 * unreadable zip wedges resource pack delivery permanently: the merge never
 * completes, and the log fills with the same failure forever.</p>
 *
 * <p>After {@link #FAILURES_BEFORE_QUARANTINE} consecutive failures the pack is
 * dropped from the merge so the remaining packs can publish. This is a
 * deliberate trade: an incomplete merge that works beats a complete merge that
 * never ships, and the operator is told exactly which pack was dropped.</p>
 *
 * <p>Records are keyed on the pack's path <em>and</em> a cheap size/mtime
 * fingerprint, so repairing or replacing the file clears its record
 * automatically and the pack rejoins the next merge with no admin action and no
 * restart.</p>
 */
public final class PackQuarantine {
    /**
     * Retries before giving up on a pack. More than one because a pack being
     * rewritten on disk can genuinely fail a read that would succeed moments
     * later; three consecutive identical failures is no longer transient.
     */
    private static final int FAILURES_BEFORE_QUARANTINE = 3;

    private static final Map<String, Record> records = new ConcurrentHashMap<>();

    private PackQuarantine() {
    }

    private static final class Record {
        private final String fingerprint;
        private final int failures;
        private final boolean announced;

        private Record(String fingerprint, int failures, boolean announced) {
            this.fingerprint = fingerprint;
            this.failures = failures;
            this.announced = announced;
        }
    }

    /**
     * Stable identity for a pack across merges. Canonical where possible so the
     * same file reached by a different relative path is one record, not two.
     */
    private static String key(File pack) {
        try {
            return pack.getCanonicalPath();
        } catch (Exception exception) {
            return pack.getAbsolutePath();
        }
    }

    /**
     * Cheap content-change signal. Deliberately not a hash: this runs on every
     * merge for every pack, and size plus mtime is enough to notice that an
     * operator repaired or replaced the file. Directories report their own
     * mtime, which changes when their contents are rewritten.
     */
    private static String fingerprint(File pack) {
        return pack.length() + ":" + pack.lastModified() + ":" + pack.exists();
    }

    /**
     * Records one staging failure for {@code pack}.
     *
     * @return {@code true} when this failure is the one that quarantines the
     *         pack, so the caller can re-arm the mix immediately instead of
     *         waiting for an unrelated change to retrigger it
     */
    public static boolean recordFailure(File pack) {
        if (pack == null) return false;
        String key = key(pack);
        String fingerprint = fingerprint(pack);

        Record previous = records.get(key);
        // A changed fingerprint means this is a different pack version than the one
        // that failed before, so its failure count starts over rather than inheriting
        // the old file's history.
        int failures = (previous != null && previous.fingerprint.equals(fingerprint))
                ? previous.failures + 1
                : 1;
        boolean announced = previous != null
                && previous.fingerprint.equals(fingerprint)
                && previous.announced;

        boolean newlyQuarantined = failures >= FAILURES_BEFORE_QUARANTINE && !announced;
        if (newlyQuarantined) {
            announced = true;
            Logger.warn("Resource pack " + pack.getName() + " failed to stage "
                    + failures + " times in a row and has been excluded from the merge so the "
                    + "remaining packs can be delivered. The merged pack is now INCOMPLETE: "
                    + "content from this pack is missing for all players. Repair or replace "
                    + pack.getAbsolutePath() + " (a corrupt or partially written zip is the usual "
                    + "cause), or exclude it deliberately in the mixer configuration. It rejoins "
                    + "the merge automatically once the file changes.");
        }
        records.put(key, new Record(fingerprint, failures, announced));
        return newlyQuarantined;
    }

    /**
     * @return {@code true} when this exact pack version has already exhausted
     *         its retries and must be left out of the merge
     */
    public static boolean isQuarantined(File pack) {
        if (pack == null) return false;
        Record record = records.get(key(pack));
        if (record == null || record.failures < FAILURES_BEFORE_QUARANTINE) return false;
        // A repaired file has a different fingerprint, which retires the quarantine
        // without the operator having to tell us the pack is fixed.
        if (!record.fingerprint.equals(fingerprint(pack))) {
            records.remove(key(pack));
            RSPLogger.detail("Resource pack " + pack.getName()
                    + " changed on disk; clearing its quarantine and retrying it in the next merge.");
            return false;
        }
        return true;
    }

    /**
     * Drops failure counts that never reached the quarantine threshold once a
     * merge completes, so unrelated transient failures spread over a long
     * uptime cannot accumulate into a quarantine. Standing quarantines are
     * deliberately preserved: clearing them here would re-add the bad pack and
     * restore the very loop this class exists to break.
     */
    public static void noteSuccessfulMix() {
        records.entrySet().removeIf(entry -> entry.getValue().failures < FAILURES_BEFORE_QUARANTINE);
    }

    /**
     * Removes every record. Intended for shutdown/reload so a fresh cycle
     * re-evaluates each pack from scratch.
     */
    public static void reset() {
        records.clear();
    }

    /**
     * Filters quarantined packs out of a merge input list.
     *
     * <p>The loud operator-facing warning is emitted once, when the pack is
     * quarantined. This per-merge line stays at detail level so a standing
     * quarantine does not drown the log on servers that remix often, while
     * still leaving a trace in every merge that shipped without the pack.</p>
     *
     * @return the packs that should actually be merged
     */
    public static List<File> excludeQuarantined(List<File> orderedPacks) {
        if (orderedPacks == null || orderedPacks.isEmpty()) return orderedPacks;
        List<File> usable = new ArrayList<>(orderedPacks.size());
        List<String> excluded = new ArrayList<>();
        for (File pack : orderedPacks) {
            if (isQuarantined(pack)) excluded.add(pack.getName());
            else usable.add(pack);
        }
        if (!excluded.isEmpty()) {
            RSPLogger.detail("Merging without " + excluded.size() + " quarantined pack(s): "
                    + String.join(", ", excluded)
                    + ". The delivered pack is missing their content until they are repaired.");
        }
        return usable;
    }
}
