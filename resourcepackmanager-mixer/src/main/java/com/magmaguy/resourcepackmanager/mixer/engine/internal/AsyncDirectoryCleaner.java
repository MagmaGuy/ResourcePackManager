package com.magmaguy.resourcepackmanager.mixer.engine.internal;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deletes large staging trees off the critical path.
 *
 * <p>A mix cycle ends by recursively deleting tens of thousands of expanded pack files. None of
 * that work is needed before the pack can be published — it is pure disk reclamation — but it used
 * to run inline, so {@code /rspm reload} did not return until the last file was unlinked.</p>
 *
 * <h2>Rename first, then delete</h2>
 * <p>Handing a live directory to a background thread would race the next mix cycle, which wipes and
 * recreates the same paths. So the directory is first renamed to a uniquely-named
 * {@code .rspm_trash_<nanos>} sibling. That is a single metadata operation, and it frees the
 * original path <i>immediately</i> — the next cycle can recreate it while the old contents are
 * still being unlinked, with no possibility of the cleaner deleting files the new cycle just
 * wrote.</p>
 *
 * <p>If the rename fails — Windows keeps a directory locked while any handle inside it is open —
 * the caller falls back to deleting synchronously, exactly as before. Correctness never depends on
 * the async path succeeding.</p>
 *
 * <p>Trash directories left behind by a crash or a shutdown mid-delete are swept by
 * {@link #sweepTrash(File)} at the start of the next mix.</p>
 */
public final class AsyncDirectoryCleaner {

    public static final String TRASH_PREFIX = ".rspm_trash_";

    private static final ExecutorService CLEANER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "RSPM-staging-cleaner");
        // Daemon: a pending delete must never hold up server shutdown. Anything still queued is
        // swept on the next boot by sweepTrash().
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    private static final AtomicInteger TRASH_COUNTER = new AtomicInteger();

    private AsyncDirectoryCleaner() {
    }

    /**
     * Frees {@code directory}'s path immediately and reclaims its disk space in the background.
     *
     * @return {@code true} if the delete was handed off, {@code false} if the caller must delete
     *         {@code directory} synchronously itself
     */
    public static boolean deleteLater(File directory) {
        if (directory == null || !directory.exists()) return true;
        File parent = directory.getParentFile();
        if (parent == null) return false;

        File trash = new File(parent, TRASH_PREFIX
                + Long.toHexString(System.nanoTime()) + "_" + TRASH_COUNTER.incrementAndGet());
        if (!directory.renameTo(trash)) {
            // Path still occupied, so the next cycle would collide with a background delete.
            return false;
        }
        return submit(trash);
    }

    /**
     * Reclaims {@code directory}, preferring the background hand-off and falling back to a
     * synchronous recursive delete when the rename fails.
     */
    public static void delete(File directory) {
        if (deleteLater(directory)) return;
        recursivelyDelete(directory);
    }

    /**
     * Removes trash directories orphaned by an earlier crash or shutdown. Their names are already
     * unique, so no rename is needed and they can be deleted in the background directly.
     */
    public static void sweepTrash(File parent) {
        if (parent == null || !parent.isDirectory()) return;
        File[] children = parent.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory() && child.getName().startsWith(TRASH_PREFIX)) {
                submit(child);
            }
        }
    }

    private static boolean submit(File trash) {
        try {
            CLEANER.execute(() -> recursivelyDelete(trash));
            return true;
        } catch (RejectedExecutionException shuttingDown) {
            recursivelyDelete(trash);
            return true;
        }
    }

    private static void recursivelyDelete(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) recursivelyDelete(child);
            }
        }
        // Cleanup failures are usually transient (file locks, AV scanners, GC delays releasing zip
        // handles). Silent: the next mix cycle's sweep catches leftovers, and a genuinely wedged
        // disk surfaces via the subsequent zip/copy failure with an actionable error.
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException | RuntimeException ignored) {
        }
    }
}
