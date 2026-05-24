package com.magmaguy.rspm.mixer;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.magmaguy.rspm.mixer.internal.Sha1;
import com.magmaguy.rspm.mixer.internal.ZipUtil;

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
    private final MergeOperations merge;

    public MixEngine(MixerLogger logger) {
        this.logger = logger;
        this.merge = new MergeOperations(logger);
    }

    public MixOutput run(MixInput input) throws IOException {
        // 1. Setup.
        File workingDir = input.workingDir();
        File outputDir = input.outputDir();
        if (!workingDir.exists()) workingDir.mkdirs();
        if (!outputDir.exists()) outputDir.mkdirs();

        List<String> collisionLog = new ArrayList<>();
        // Track every staging directory we materialize so we can clean it up later
        // without scanning outputDir blindly (which would also nuke unrelated artifacts
        // dropped there by platform-side post-processing — e.g. Bedrock outputs).
        List<File> stagingDirs = new ArrayList<>();

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
                recursivelyCopyDirectory(subFile, mergedDir, collisionLog);
            }
        }

        // Atlas-overlay reconciliation must happen AFTER all packs have merged into mergedDir,
        // because it reads pack.mcmeta (which itself was JSON-merged from every input).
        merge.mergeBaseAtlasSourcesIntoOverlays(mergedDir);

        // 4. Zip.
        File mergedZip = new File(outputDir, input.outputName() + ".zip");
        if (!ZipUtil.zip(mergedDir, mergedZip.getAbsolutePath())) {
            throw new IOException("Failed to zip merged resource pack into " + mergedZip.getAbsolutePath());
        }

        // 5. SHA-1.
        byte[] sha1Bytes = Sha1.bytes(mergedZip);
        String sha1Hex = Sha1.bytesToHexString(sha1Bytes);

        // 6. Collision log.
        if (input.writeCollisionLog()) {
            writeCollisionLog(outputDir, collisionLog);
        }

        // 7. Cleanup: only the per-pack staging dirs we materialized. The merged
        //    unzipped folder is left in place because downstream wrappers (Bedrock
        //    conversion, reroute-copy, etc.) need filesystem access to it.
        for (File staged : stagingDirs) {
            if (staged.exists()) {
                recursivelyDeleteDirectory(staged);
            }
        }

        return new MixOutput(
                mergedZip,
                mergedDir,
                sha1Hex,
                sha1Bytes,
                Collections.unmodifiableList(collisionLog)
        );
    }

    /**
     * Materialize a single input pack inside {@code outputDir}. Zip files are
     * unzipped under {@code <outputDir>/<name-without-.zip>/}; directories are
     * copied under {@code <outputDir>/cluster_<dirname>/} (matching the legacy
     * {@code Mix.copyClusterDirectoriesToOutput} behaviour, which keeps the
     * extra wrapper so the assembly-phase walk treats it uniformly).
     */
    private File stagePack(File pack, File outputDir) throws IOException {
        if (pack.isDirectory()) {
            File wrapper = new File(outputDir, "cluster_" + pack.getName());
            if (!wrapper.exists()) wrapper.mkdirs();
            recursivelyCopyDirectoryRaw(pack, wrapper);
            return wrapper;
        }
        // Strip the .zip suffix if present so the staged folder name matches the
        // pack name (callers may pass non-zip files; treat them as raw copies).
        String name = pack.getName();
        String staged = name.endsWith(".zip") ? name.substring(0, name.length() - 4) : name;
        File destination = new File(outputDir, staged);
        // Pre-create so getCanonicalPath() inside ZipUtil works for the zip-slip guard
        if (!destination.exists()) destination.mkdirs();
        if (name.endsWith(".zip")) {
            ZipUtil.unzip(pack, destination);
        } else {
            // Non-zip, non-directory input (rare): copy verbatim into the destination.
            Files.copy(pack.toPath(), new File(destination, name).toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return destination;
    }

    /**
     * Recursively copy a single tree under {@code target}, with collision-aware
     * merging for JSON files. This is the assembly-phase workhorse; it threads
     * the collision log so {@link #resolveFileCollision} can report each merge
     * outcome without depending on engine state.
     */
    private void recursivelyCopyDirectory(File source, File target, List<String> collisionLog) {
        if (source.isDirectory()) {
            String sourceName = source.getName();
            File nestedTarget = new File(target.getAbsolutePath() + File.separatorChar + sourceName);
            nestedTarget.mkdir();
            File[] children = source.listFiles();
            if (children != null) {
                for (File file : children) {
                    recursivelyCopyDirectory(file, nestedTarget, collisionLog);
                }
            }
        } else {
            try {
                Path targetPath = Path.of(target.getPath() + File.separatorChar + source.getName());
                if (targetPath.toFile().exists()) {
                    resolveFileCollision(source, targetPath.toFile(), collisionLog);
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
            try {
                Files.delete(directory.toPath());
            } catch (Exception e) {
                logger.warn("Failed to delete directory " + directory.getPath());
            }
        } else {
            try {
                Files.delete(directory.toPath());
            } catch (IOException e) {
                logger.warn("Failed to delete file " + directory.getPath());
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
    private void resolveFileCollision(File sourceFile, File targetFile, List<String> collisionLog) throws IOException {
        if (targetFile.getName().equals("pack.mcmeta")) {
            merge.mergePackMcmeta(sourceFile, targetFile);
            return;
        }

        if (!targetFile.getName().endsWith(".json")) {
            collisionLog.add("Kept (higher priority): " + targetFile.getPath());
            return;
        }

        if (!merge.isMergeableJsonFile(targetFile)) {
            collisionLog.add("Kept (higher priority, non-mergeable JSON): " + targetFile.getPath());
            return;
        }

        JsonObject json1 = merge.readJsonFile(sourceFile);
        JsonObject json2 = merge.readJsonFile(targetFile);

        if (json1 == null && json2 == null) {
            logger.warn("Both JSON files unreadable during merge, skipping: " + targetFile.getPath());
            return;
        }
        if (json1 == null) return;
        if (json2 == null) {
            Files.copy(sourceFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            collisionLog.add("Replaced (unreadable target JSON): " + targetFile.getPath());
            return;
        }

        JsonObject mergedJson;
        if (merge.isItemsFile(targetFile)) {
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

        collisionLog.add("Merged: " + targetFile.getPath());
    }

    private void writeCollisionLog(File outputDir, List<String> collisionLog) {
        if (collisionLog == null || collisionLog.isEmpty()) return;
        File logFile = new File(outputDir, "collision_log.txt");
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
