package com.magmaguy.resourcepackmanager.mixer.engine;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.magmaguy.resourcepackmanager.mixer.engine.internal.Sha1;
import com.magmaguy.resourcepackmanager.mixer.engine.internal.ZipUtil;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    public MixEngine(MixerLogger logger) {
        this.logger = logger;
    }

    public MixOutput run(MixInput input) throws IOException {
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
        // MergeOperations (e.g. mergePackMcmeta line 349, mergeBaseAtlasSourcesIntoOverlays
        // line 221) feeds into this run's collisionLog. info/warn still forward to the
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
            List<File> unzippedPackDirs = new ArrayList<>();
            for (File pack : input.orderedPacks()) {
                if (pack == null) {
                    logger.warn("A resource pack was null by the time it was meant to be unzipped!");
                    continue;
                }
                try {
                    File staged = stagePack(pack, outputDir);
                    if (staged != null) {
                        unzippedPackDirs.add(staged);
                        stagingDirs.add(staged);
                    }
                } catch (Exception e) {
                    logger.warn("Failed to stage resource pack " + pack.getName()
                            + " - the file might be encrypted or the plugin distributes its own pack. This pack will be skipped.");
                    logger.warn("Error details: " + e.getMessage());
                }
            }

            // 3. Assembly: wipe the merged staging dir, then walk-copy every pack into it
            //    in priority order. Collisions are routed to resolveFileCollision below.
            File mergedDir = new File(outputDir, input.outputName());
            if (mergedDir.exists()) {
                recursivelyDeleteDirectory(mergedDir);
            }
            if (!mergedDir.mkdirs() && !mergedDir.exists()) {
                throw new IOException("Failed to create merged staging directory: " + mergedDir.getAbsolutePath());
            }

            for (File packDir : unzippedPackDirs) {
                if (!packDir.exists()) continue;
                if (!packDir.isDirectory()) {
                    logger.warn("Expected staged pack to be a directory but it isn't: " + packDir.getAbsolutePath());
                    continue;
                }
                File[] subFiles = packDir.listFiles();
                if (subFiles == null) continue;
                for (File subFile : subFiles) {
                    recursivelyCopyDirectory(subFile, mergedDir, merge, wrapped);
                }
            }

            // Atlas-overlay reconciliation must happen AFTER all packs have merged into mergedDir,
            // because it reads pack.mcmeta (which itself was JSON-merged from every input).
            merge.mergeBaseAtlasSourcesIntoOverlays(mergedDir);
            merge.sanitizeMergedModels(mergedDir);

            // Final validation pass over the merged pack.mcmeta. WARN ONLY — we do not rewrite
            // user content. Any overlay entry whose range dips below the old/new pack-format
            // boundary but lacks a valid `formats` field will be rejected by MC 1.21.9+ clients;
            // warn loudly (naming the entry) so admins get an actionable message instead of a
            // silent client-side rejection.
            merge.warnOnInvalidOverlayMetadata(mergedDir);

            // 4. Zip.
            File mergedZip = new File(outputDir, input.outputName() + ".zip");
            if (!ZipUtil.zip(mergedDir, mergedZip.getAbsolutePath())) {
                throw new IOException("Failed to zip merged resource pack into " + mergedZip.getAbsolutePath());
            }

            // 5. SHA-1.
            byte[] sha1Bytes = Sha1.bytes(mergedZip);
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
            for (File staged : stagingDirs) {
                if (staged.exists()) {
                    recursivelyDeleteDirectory(staged);
                }
            }
        }
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
    private File stagePack(File pack, File outputDir) throws IOException {
        File destination;
        boolean isDirectoryInput = pack.isDirectory();
        if (isDirectoryInput) {
            destination = new File(outputDir, "cluster_" + pack.getName());
        } else {
            // Strip the .zip suffix if present so the staged folder name matches the
            // pack name (callers may pass non-zip files; treat them as raw copies).
            String name = pack.getName();
            String staged = name.endsWith(".zip") ? name.substring(0, name.length() - 4) : name;
            destination = new File(outputDir, staged);
        }

        // Wipe-before-stage: previous-run leftovers (crashed cleanup, removed files
        // in the new pack version) must not bleed into the new assembly.
        if (destination.exists()) {
            recursivelyDeleteDirectory(destination);
        }
        // Pre-create so getCanonicalPath() inside ZipUtil works for the zip-slip guard.
        destination.mkdirs();

        if (isDirectoryInput) {
            recursivelyCopyDirectoryRaw(pack, destination);
        } else {
            String name = pack.getName();
            if (name.endsWith(".zip")) {
                ZipUtil.unzip(pack, destination);
            } else {
                // Non-zip, non-directory input (rare): copy verbatim into the destination.
                Files.copy(pack.toPath(), new File(destination, name).toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return destination;
    }

    /**
     * Recursively copy a single tree under {@code target}, with collision-aware
     * merging for JSON files. This is the assembly-phase workhorse; it threads
     * the run-scoped {@code merge} + {@code wrapped} logger so collision entries
     * flow into the same per-run list that {@link MergeOperations} writes to.
     */
    private void recursivelyCopyDirectory(File source, File target, MergeOperations merge, MixerLogger wrapped) {
        if (source.isDirectory()) {
            String sourceName = source.getName();
            File nestedTarget = new File(target.getAbsolutePath() + File.separatorChar + sourceName);
            nestedTarget.mkdir();
            File[] children = source.listFiles();
            if (children != null) {
                for (File file : children) {
                    recursivelyCopyDirectory(file, nestedTarget, merge, wrapped);
                }
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
                Files.copy(source.toPath(), targetPath);
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
        if (source.isDirectory()) {
            File nestedTarget = new File(target, source.getName());
            if (!nestedTarget.exists()) nestedTarget.mkdirs();
            File[] children = source.listFiles();
            if (children != null) {
                for (File file : children) {
                    recursivelyCopyDirectoryRaw(file, nestedTarget);
                }
            }
        } else {
            File parent = target;
            if (!parent.exists()) parent.mkdirs();
            File dest = new File(parent, source.getName());
            Files.copy(source.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void recursivelyDeleteDirectory(File directory) {
        if (directory.isDirectory()) {
            File[] children = directory.listFiles();
            if (children != null) {
                for (File file : children) {
                    recursivelyDeleteDirectory(file);
                }
            }
            // Cleanup failures are usually transient (file locks, AV scanners,
            // GC delays releasing zip handles). Silent — the next mix cycle's
            // pre-clean catches any leftover, and any real "disk is wedged"
            // condition surfaces via the subsequent zip / copy failure with
            // an actionable error.
            try { Files.delete(directory.toPath()); } catch (Exception ignored) {}
        } else {
            try { Files.delete(directory.toPath()); } catch (IOException ignored) {}
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
        if (targetFile.getName().equals("pack.mcmeta")) {
            // mergePackMcmeta already emits its own logger.collision(...) entry, which the
            // wrapped logger routes into the run-scoped list. No additional logging here.
            merge.mergePackMcmeta(sourceFile, targetFile);
            return;
        }

        if (!targetFile.getName().endsWith(".json")) {
            wrapped.collision("Kept (higher priority): " + targetFile.getPath());
            return;
        }

        if (!merge.isMergeableJsonFile(targetFile)) {
            wrapped.collision("Kept (higher priority, non-mergeable JSON): " + targetFile.getPath());
            return;
        }

        JsonObject json1 = merge.readJsonFile(sourceFile);
        JsonObject json2 = merge.readJsonFile(targetFile);

        if (json1 == null && json2 == null) {
            wrapped.warn("Both JSON files unreadable during merge, skipping: " + targetFile.getPath());
            return;
        }
        if (json1 == null) return;
        if (json2 == null) {
            Files.copy(sourceFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            wrapped.collision("Replaced (unreadable target JSON): " + targetFile.getPath());
            return;
        }

        JsonObject mergedJson;
        if (merge.isLegacyItemModel(targetFile)) {
            mergedJson = merge.mergeLegacyItemModelOverrides(json1, json2);
        } else if (merge.isItemsFile(targetFile)) {
            mergedJson = merge.mergeItemsModels(json1, json2);
        } else if (targetFile.getName().equals("sounds.json")) {
            mergedJson = merge.mergeSoundsJson(json1, json2);
        } else {
            mergedJson = merge.mergeJsonObjects(json1, json2);
        }

        // Legacy item models (pre-1.21.4) sort overrides by custom_model_data so
        // numeric ordering survives a deep merge — without this the higher CMD
        // entries can leapfrog lower ones and the wrong model resolves at runtime.
        if (merge.isLegacyItemModel(targetFile) && mergedJson.has("overrides")) {
            merge.sortModelOverrides(mergedJson);
        }

        try (FileWriter writer = new FileWriter(targetFile)) {
            new Gson().toJson(mergedJson, writer);
        }

        wrapped.collision("Merged: " + targetFile.getPath());
    }

    private void writeCollisionLog(File collisionLogDir, List<String> collisionLog) {
        if (collisionLog == null || collisionLog.isEmpty()) return;
        if (collisionLogDir == null) {
            logger.warn("Collision log requested but no collisionLogDir was supplied — skipping.");
            return;
        }
        if (!collisionLogDir.exists()) collisionLogDir.mkdirs();
        File logFile = new File(collisionLogDir, "collision_log.txt");
        try (FileWriter writer = new FileWriter(logFile, false)) {
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
