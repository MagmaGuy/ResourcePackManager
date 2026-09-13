package com.magmaguy.resourcepackmanager.mixer.engine;

import com.magmaguy.resourcepackmanager.mixer.engine.internal.AsyncDirectoryCleaner;
import com.magmaguy.resourcepackmanager.mixer.engine.internal.Cancellation;
import com.magmaguy.resourcepackmanager.mixer.engine.internal.Sha1;
import com.magmaguy.resourcepackmanager.mixer.engine.internal.ZipUtil;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Platform-neutral resource-pack mixing pipeline. Given an ordered list of
 * input packs (zip files or already-unzipped directories), produces a single
 * merged zip plus its SHA-1, resolving JSON collisions via {@link MergeOperations}.
 *
 * <p>The engine has no dependency on Bukkit, MagmaCore, or the
 * {@code com.magmaguy.resourcepackmanager} module — it is callable from proxy
 * plugins or test harnesses that want the same merge logic without dragging in
 * a server runtime.</p>
 *
 * <p>The engine deliberately leaves the unzipped merged staging folder
 * ({@code <outputDir>/<outputName>/}) on disk after zipping; see
 * {@link MixOutput#mergedDir()}. Cleanup of per-pack unzipped staging dirs is
 * performed before return.</p>
 */
public final class MixEngine {
    private final MixerLogger logger;
    private final BooleanSupplier cancellationRequested;

    public MixEngine(MixerLogger logger, BooleanSupplier cancellationRequested) {
        this.logger = logger;
        this.cancellationRequested = cancellationRequested == null ? () -> false : cancellationRequested;
    }

    public MixOutput run(MixInput input) throws IOException {
        checkCancelled();
        // 1. Setup.
        File workingDir = input.workingDir();
        File outputDir = input.outputDir();
        if (!workingDir.exists()) workingDir.mkdirs();
        if (!outputDir.exists()) outputDir.mkdirs();

        // Run-scoped state. We allocate it inside run() rather than as fields so the
        // engine remains safe to reuse across calls and to share between threads — each
        // invocation has its own collision list and its own MergeOperations.
        List<String> collisionLog = new ArrayList<>();

        // Decorate the injected logger so any logger.collision(...) call coming out of
        // MergeOperations (e.g. mergePackMcmeta, mergeBaseAtlasSourcesIntoOverlays)
        // feeds into this run's collisionLog. info/warn still forward to the
        // platform logger. The engine itself routes its own collision entries through the
        // same wrapped.collision(...) channel so everything funnels through one place.
        MixerLogger wrapped = new MixerLogger() {
            @Override public void info(String m) { logger.info(m); }
            @Override public void warn(String m) { logger.warn(m); }
            @Override public void collision(String m) { collisionLog.add(m); }
        };
        MergeOperations merge = new MergeOperations(wrapped);

        // Track every staging directory we materialize so we can clean it up later
        // without scanning outputDir blindly (which would also nuke unrelated artifacts
        // dropped there by platform-side post-processing — e.g. Bedrock outputs).
        List<File> stagingDirs = new ArrayList<>();

        try {
            // 2. Unzip phase: expand each input pack into outputDir under a per-pack folder
            //    (or copy if the input is already a directory). Maintain the ordering so
            //    higher-priority packs win collisions in the assembly phase.
            pruneStaleStageDirectories(outputDir);
            List<File> unzippedPackDirs = new ArrayList<>();
            List<File> uniqueInputs = removeExactDuplicateFileInputs(input.orderedPacks());
            for (int inputIndex = 0; inputIndex < uniqueInputs.size(); inputIndex++) {
                File pack = uniqueInputs.get(inputIndex);
                checkCancelled();
                if (pack == null) {
                    throw new IOException("Resource pack input #" + inputIndex
                            + " was null by the time it was meant to be staged");
                }
                try {
                    File staged = stagePack(pack, outputDir, inputIndex);
                    if (staged != null) {
                        unzippedPackDirs.add(staged);
                        stagingDirs.add(staged);
                    }
                } catch (CancellationException cancelled) {
                    throw cancelled;
                } catch (Exception e) {
                    // Typed so the caller can quarantine this exact pack. A bad pack fails
                    // identically on every attempt, so an untyped failure leaves the caller
                    // with nothing to act on but the message string.
                    throw new PackStagingException(pack, "Failed to stage enabled resource pack "
                            + pack.getAbsolutePath()
                            + ". Exclude or repair this pack instead of publishing an incomplete merge.", e);
                }
            }

            // 3. Assembly: wipe the merged staging dir, then walk-copy every pack into it
            //    in priority order. Collisions are routed to resolveFileCollision below.
            File mergedDir = new File(outputDir, input.outputName());
            checkCancelled();
            if (mergedDir.exists()) {
                // Renaming the previous cycle's merged tree aside frees the path instantly, so
                // assembly starts now instead of after ~25k unlink calls.
                deleteDirectoryOffCriticalPath(mergedDir);
            }
            checkCancelled();
            if (!mergedDir.mkdirs() && !mergedDir.exists()) {
                throw new IOException("Failed to create merged staging directory: " + mergedDir.getAbsolutePath());
            }

            for (File packDir : unzippedPackDirs) {
                checkCancelled();
                if (!packDir.exists()) continue;
                if (!packDir.isDirectory()) {
                    logger.warn("Expected staged pack to be a directory but it isn't: " + packDir.getAbsolutePath());
                    continue;
                }
                File[] subFiles = sortedChildren(packDir);
                warnOnLikelyNestedRoot(packDir, subFiles);
                for (File subFile : subFiles) {
                    checkCancelled();
                    recursivelyCopyDirectory(subFile, mergedDir, merge, wrapped);
                }
            }

            // Check overlay directory containment before any code resolves those directories on
            // disk. Format metadata is intentionally preserved from the source packs.
            checkCancelled();
            merge.normalizeAndValidateOverlayMetadata(mergedDir);
            checkCancelled();

            // Atlas-overlay reconciliation must happen AFTER all packs have merged into mergedDir,
            // because it reads pack.mcmeta (which itself was JSON-merged from every input).
            merge.mergeBaseAtlasSourcesIntoOverlays(mergedDir);
            checkCancelled();
            new BlocksAtlasSanitizer(wrapped).sanitize(mergedDir);
            checkCancelled();
            merge.sanitizeMergedModels(mergedDir);
            checkCancelled();

            // 4. Zip.
            File mergedZip = new File(outputDir, input.outputName() + ".zip");
            ZipUtil.ZipResult zipResult = ZipUtil.zipJavaResourcePackWithSha1(
                    mergedDir, mergedZip.getAbsolutePath(), cancellationRequested);
            checkCancelled();
            if (!zipResult.success()) {
                throw new IOException("Failed to zip merged resource pack into " + mergedZip.getAbsolutePath());
            }

            // 5. SHA-1. ZipUtil calculated this over the final compressed bytes
            // while writing them, avoiding an immediate full-file reread.
            byte[] sha1Bytes = zipResult.sha1Bytes();
            String sha1Hex = Sha1.bytesToHexString(sha1Bytes);

            // 6. Collision log. Written to collisionLogDir (the plugin data folder on the
            //    Bukkit side) rather than outputDir, matching the legacy on-disk path so
            //    users and scripts referencing dataFolder/collision_log.txt keep working.
            if (input.writeCollisionLog()) {
                writeCollisionLog(input.collisionLogDir(), collisionLog);
            }

            return new MixOutput(
                    mergedZip,
                    mergedDir,
                    sha1Hex,
                    sha1Bytes,
                    Collections.unmodifiableList(collisionLog)
            );
        } finally {
            // 7. Cleanup: only the per-pack staging dirs we materialized. The merged
            //    unzipped folder is left in place because downstream wrappers (Bedrock
            //    conversion, reroute-copy, etc.) need filesystem access to it. Done in
            //    finally so a mid-assembly exception doesn't leave staging litter behind.
            // A cancellation is a latency-sensitive shutdown path. Retain any
            // partial run-scoped staging trees and let the next mix's mandatory
            // wipe-before-stage remove them, instead of making disable spend
            // seconds recursively deleting thousands of expanded pack files.
            if (!isCancelled()) {
                for (File staged : stagingDirs) {
                    if (staged.exists()) {
                        deleteDirectoryOffCriticalPath(staged);
                    }
                }
            }
        }
    }

    /**
     * Drop only byte-for-byte duplicate file inputs while preserving the first
     * occurrence (and therefore the configured priority order).
     *
     * <p>Several plugins can legitimately expose the same shaded/shared
     * resource pack under different filenames. Feeding every copy through the
     * merger wastes an unzip, a complete tree copy, and collision processing.
     * It can also multiply entries in mergeable JSON arrays such as font
     * providers. Files are bucketed by length before a cancellable byte comparison so
     * the normal case of differently-sized packs adds metadata checks only;
     * directories and unreadable/non-regular inputs retain the old staging
     * path unchanged.</p>
     */
    private List<File> removeExactDuplicateFileInputs(List<File> orderedPacks) {
        Map<Long, List<File>> representativesByLength = new HashMap<>();
        List<File> unique = new ArrayList<>(orderedPacks.size());

        for (File pack : orderedPacks) {
            checkCancelled();
            if (pack == null || !pack.isFile()) {
                unique.add(pack);
                continue;
            }

            try {
                long length = Files.size(pack.toPath());
                List<File> representatives = representativesByLength
                        .computeIfAbsent(length, ignored -> new ArrayList<>());

                File duplicateOf = null;
                for (File representative : representatives) {
                    checkCancelled();
                    if (filesEqual(representative, pack)) {
                        duplicateOf = representative;
                        break;
                    }
                }

                if (duplicateOf != null) {
                    logger.info("Skipping byte-identical resource pack " + pack.getName()
                            + "; it duplicates " + duplicateOf.getName() + ".");
                    continue;
                }

                representatives.add(pack);
            } catch (IOException ignored) {
                // Preserve the old behavior for files that cannot be inspected:
                // the staging phase below will either read them or emit its
                // existing actionable warning.
            }
            unique.add(pack);
        }

        return unique;
    }

    /**
     * Materialize a single input pack inside {@code outputDir}. Zip files are
     * unzipped under {@code <outputDir>/<name-without-.zip>/}; directories are
     * copied under {@code <outputDir>/cluster_<dirname>/} (matching the legacy
     * {@code Mix.copyClusterDirectoriesToOutput} behaviour, which keeps the
     * extra wrapper so the assembly-phase walk treats it uniformly).
     *
     * <p>The destination is wiped before staging so that leftovers from a prior
     * crashed run (or from a pack that shrank between runs) can't leak into the
     * merged output. The legacy pipeline got this for free via the end-of-run
     * outputFolder-wide sweep; this engine only deletes its own tracked staging
     * dirs in cleanup, so the per-stage pre-wipe matters here.</p>
     */
    private File stagePack(File pack, File outputDir, int inputIndex) throws IOException {
        checkCancelled();
        File destination;
        boolean isDirectoryInput = pack.isDirectory();
        String stagePrefix = String.format(java.util.Locale.ROOT, ".rspm_stage_%04d_", inputIndex);
        if (isDirectoryInput) {
            destination = new File(outputDir, stagePrefix + "cluster_" + pack.getName());
        } else {
            // Strip the .zip suffix if present so the staged folder name matches the
            // pack name (callers may pass non-zip files; treat them as raw copies).
            String name = pack.getName();
            String staged = name.endsWith(".zip") ? name.substring(0, name.length() - 4) : name;
            destination = new File(outputDir, stagePrefix + staged);
        }

        // Wipe-before-stage: previous-run leftovers (crashed cleanup, removed files
        // in the new pack version) must not bleed into the new assembly.
        if (destination.exists()) {
            deleteDirectoryOffCriticalPath(destination);
        }
        checkCancelled();
        // Pre-create the staging root the unzip/copy will populate.
        destination.mkdirs();

        if (isDirectoryInput) {
            recursivelyCopyDirectoryRaw(pack, destination);
        } else {
            String name = pack.getName();
            if (name.endsWith(".zip")) {
                ZipUtil.unzip(pack, destination, cancellationRequested);
            } else {
                // Non-zip, non-directory input (rare): copy verbatim into the destination.
                copyFile(pack, new File(destination, name), true);
            }
        }
        checkCancelled();
        return destination;
    }

    /**
     * Recursively copy a single tree under {@code target}, with collision-aware
     * merging for JSON files. This is the assembly-phase workhorse; it threads
     * the run-scoped {@code merge} + {@code wrapped} logger so collision entries
     * flow into the same per-run list that {@link MergeOperations} writes to.
     */
    private void recursivelyCopyDirectory(File source, File target, MergeOperations merge, MixerLogger wrapped) {
        checkCancelled();
        if (source.isDirectory()) {
            String sourceName = source.getName();
            File nestedTarget = new File(target.getAbsolutePath() + File.separatorChar + sourceName);
            nestedTarget.mkdir();
            for (File file : sortedChildren(source)) {
                checkCancelled();
                recursivelyCopyDirectory(file, nestedTarget, merge, wrapped);
            }
        } else {
            try {
                Path targetPath = Path.of(target.getPath() + File.separatorChar + source.getName());
                if (targetPath.toFile().exists()) {
                    resolveFileCollision(source, targetPath.toFile(), merge, wrapped);
                    return;
                }
                Path parent = targetPath.getParent();
                if (parent != null) parent.toFile().mkdirs();
                copyFile(source, targetPath.toFile(), false);
            } catch (IOException e) {
                throw new RuntimeException("Failed to copy file " + source.getAbsolutePath(), e);
            }
        }
    }

    /**
     * Plain-copy version used while staging cluster directories: no collision
     * handling, just mirror the tree. The merge stage above is where collisions
     * actually get resolved.
     */
    private void recursivelyCopyDirectoryRaw(File source, File target) throws IOException {
        checkCancelled();
        if (source.isDirectory()) {
            File nestedTarget = new File(target, source.getName());
            if (!nestedTarget.exists()) nestedTarget.mkdirs();
            for (File file : sortedChildren(source)) {
                checkCancelled();
                recursivelyCopyDirectoryRaw(file, nestedTarget);
            }
        } else {
            File parent = target;
            if (!parent.exists()) parent.mkdirs();
            File dest = new File(parent, source.getName());
            copyFile(source, dest, true);
        }
    }

    private boolean filesEqual(File first, File second) throws IOException {
        return Cancellation.contentEquals(first.toPath(), second.toPath(), cancellationRequested,
                "Resource pack mix cancelled");
    }

    private void copyFile(File source, File destination, boolean replaceExisting) throws IOException {
        if (!replaceExisting && destination.exists()) {
            throw new java.nio.file.FileAlreadyExistsException(destination.getPath());
        }
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(source));
             BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(destination, false))) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                checkCancelled();
                output.write(buffer, 0, read);
            }
        } catch (CancellationException cancelled) {
            try {
                Files.deleteIfExists(destination.toPath());
            } catch (IOException ignored) {
            }
            throw cancelled;
        }
    }

    private boolean isCancelled() {
        return Cancellation.isCancelled(cancellationRequested);
    }

    private void checkCancelled() {
        Cancellation.check(cancellationRequested, "Resource pack mix cancelled");
    }

    /**
     * Reclaims a staging tree without blocking the caller. The tree's path is freed synchronously
     * (via rename) so the next mix can reuse it immediately; the tens of thousands of unlink calls
     * happen on a background thread. Falls back to the synchronous delete when the rename fails.
     */
    private void deleteDirectoryOffCriticalPath(File directory) {
        if (directory == null || !directory.exists()) return;
        AsyncDirectoryCleaner.delete(directory);
    }

    /**
     * A zip whose contents are wrapped in a single top-level folder
     * ("Pack Name/assets/...") merges nothing usable and used to do so silently
     * — pack.mcmeta and assets/ must sit at the archive root.
     */
    private void warnOnLikelyNestedRoot(File packDir, File[] children) {
        if (children.length != 1 || !children[0].isDirectory()) return;
        File wrapper = children[0];
        String wrapperName = wrapper.getName();
        if (wrapperName.equals("assets") || wrapperName.equals("overlays")) return;
        if (new File(wrapper, "pack.mcmeta").exists() || new File(wrapper, "assets").isDirectory()) {
            logger.warn("Resource pack '" + packDir.getName() + "' wraps its contents in a top-level folder ('"
                    + wrapperName + "/'), so it will contribute nothing to the merge. pack.mcmeta and assets/ "
                    + "must be at the root of the zip - re-zip it from inside the '" + wrapperName + "' folder.");
        }
    }

    private static File[] sortedChildren(File directory) {
        File[] children = directory == null ? null : directory.listFiles();
        if (children == null || children.length == 0) {
            return new File[0];
        }
        Arrays.sort(children, Comparator.comparing(File::getName));
        return children;
    }

    private void pruneStaleStageDirectories(File outputDir) {
        // Trash left by a crash or a shutdown mid-delete: already uniquely named, so it can go
        // straight to the background cleaner.
        AsyncDirectoryCleaner.sweepTrash(outputDir);
        for (File child : sortedChildren(outputDir)) {
            if (child.getName().startsWith(".rspm_stage_")) {
                deleteDirectoryOffCriticalPath(child);
            }
        }
    }

    /**
     * Decide how a single file collision should be resolved during assembly.
     * The rules mirror legacy Mix.java behaviour exactly:
     * <ul>
     *   <li>{@code pack.mcmeta}: structurally merge (overlay entries, format ranges).</li>
     *   <li>Non-JSON: keep target (target is higher priority).</li>
     *   <li>Non-mergeable JSON (models, blockstates, equipment): keep target.</li>
     *   <li>Mergeable JSON: deep-merge via the format-specific helper in {@link MergeOperations}.</li>
     * </ul>
     */
    private void resolveFileCollision(File sourceFile, File targetFile, MergeOperations merge, MixerLogger wrapped) throws IOException {
        switch (merge.mergeColliding(sourceFile, targetFile)) {
            case KEPT_NON_JSON ->
                    wrapped.collision("Kept (higher priority): " + targetFile.getPath());
            case KEPT_NON_MERGEABLE_JSON ->
                    wrapped.collision("Kept (higher priority, non-mergeable JSON): " + targetFile.getPath());
            case BOTH_UNREADABLE ->
                    wrapped.warn("Both JSON files unreadable during merge, skipping: " + targetFile.getPath());
            case REPLACED_UNREADABLE_TARGET ->
                    wrapped.collision("Replaced (unreadable target JSON): " + targetFile.getPath());
            case MERGED ->
                    wrapped.collision("Merged: " + targetFile.getPath());
            case MERGED_PACK_MCMETA, KEPT_SOURCE_UNREADABLE -> {
                // mergePackMcmeta already emits its own logger.collision(...) entry, which the
                // wrapped logger routes into the run-scoped list; an unreadable source keeps the
                // target silently. No additional logging here.
            }
        }
    }

    private void writeCollisionLog(File collisionLogDir, List<String> collisionLog) {
        if (collisionLog == null || collisionLog.isEmpty()) return;
        if (collisionLogDir == null) {
            logger.warn("Collision log requested but no collisionLogDir was supplied — skipping.");
            return;
        }
        if (!collisionLogDir.exists()) collisionLogDir.mkdirs();
        File logFile = new File(collisionLogDir, "collision_log.txt");
        try (Writer writer = new BufferedWriter(new FileWriter(logFile, false), 1 << 16)) {
            writer.write("Resource Pack Collision Log\n");
            writer.write("Generated: " + java.time.LocalDateTime.now() + "\n");
            writer.write("================================================\n\n");
            for (String entry : collisionLog) {
                writer.write(entry + "\n");
            }
        } catch (IOException e) {
            logger.warn("Failed to write collision log file.");
        }
    }
}
