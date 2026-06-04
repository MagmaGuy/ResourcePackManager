package com.magmaguy.resourcepackmanager.mixer;

import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.ZipFile;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.api.ResourcePackManagerAPI;
import com.magmaguy.resourcepackmanager.autohost.AutoHost;
import com.magmaguy.resourcepackmanager.bedrock.BedrockConversion;
import com.magmaguy.resourcepackmanager.bedrock.BukkitBedrockConverterContext;
import com.magmaguy.resourcepackmanager.config.DefaultConfig;
import com.magmaguy.resourcepackmanager.thirdparty.ThirdPartyResourcePack;
import com.magmaguy.resourcepackmanager.mixer.engine.MergeOperations;
import com.magmaguy.resourcepackmanager.mixer.engine.MixEngine;
import com.magmaguy.resourcepackmanager.mixer.engine.MixInput;
import com.magmaguy.resourcepackmanager.mixer.engine.MixOutput;
import com.magmaguy.resourcepackmanager.mixer.engine.MixerLogger;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.Getter;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bukkit-side orchestrator around the platform-neutral {@link MixEngine}.
 *
 * <p>Responsibilities kept on this side because they touch Bukkit-only state:
 * scheduling the work off the main thread, locating the plugin's data folder,
 * sorting the discovered {@link ThirdPartyResourcePack}s by configured priority,
 * resource-pack rerouting (driven by {@code DefaultConfig}), and the Bedrock
 * conversion step. The actual unzip / copy-with-collision-resolution / zip /
 * SHA-1 pipeline lives in {@code resourcepackmanager-mixer}.</p>
 */
public class Mix {
    private static final String resourcePackName = "ResourcePackManager_RSP";

    // Bridge MagmaCore's Logger into the platform-neutral MixerLogger interface so the
    // engine can warn/log without any Bukkit knowledge. The engine returns collisions
    // via MixOutput.collisionLog() rather than calling back into Mix's state.
    private static final MixerLogger BUKKIT_LOGGER = new MixerLogger() {
        @Override public void info(String m) { Logger.info(m); }
        @Override public void warn(String m) { Logger.warn(m); }
        @Override public void collision(String m) { /* collected via MixOutput */ }
    };

    // Reused by recursivelyCopyDirectory below (the cluster pre-merge path used by
    // ThirdPartyResourcePack). The mixer engine instantiates its own MergeOperations
    // internally; this one is independent so the two phases don't share mutable state.
    private static final MergeOperations CLUSTER_MERGE = new MergeOperations(BUKKIT_LOGGER);

    private static File mixerFolder;
    private static List<File> orderedFiles;

    @Getter
    private static File finalResourcePack;
    @Getter
    private static String finalSHA1;
    @Getter
    private static byte[] finalSHA1Bytes;

    private Mix() {
    }

    /**
     * Mixes resource packs asynchronously. Use this from the main thread.
     */
    public static void mixResourcePacksAsync() {
        new BukkitRunnable() {
            @Override
            public void run() {
                mixResourcePacks();
            }
        }.runTaskAsynchronously(ResourcePackManager.plugin);
    }

    /**
     * Mixes resource packs synchronously. Only call this from an async context to avoid blocking the main thread.
     */
    public static void mixResourcePacks() {
        Logger.info("Starting resource pack mixing...");
        if (!initializeDefaultPluginFolders()) return;
        if (shuttingDown()) return;
        if (!ThirdPartyResourcePack.prepareResourcePacksForMix()) {
            Logger.warn("Resource pack staging failed; waiting for sources to settle before mixing.");
            ThirdPartyResourcePack.markResourcePackStagingFailed();
            return;
        }
        if (shuttingDown()) return;
        initializeThirdPartyResourcePacks();
        if (shuttingDown()) return;

        File outputFolder = getOutputFolder();
        // Collision log lands in the plugin data folder root (not output/) — preserves the
        // legacy on-disk path so existing user scripts / docs continue to find it.
        File dataFolder = ResourcePackManager.plugin.getDataFolder();

        MixInput input = new MixInput(
                orderedFiles,
                outputFolder,   // workingDir == outputDir matches legacy behaviour
                outputFolder,
                dataFolder,     // collisionLogDir
                resourcePackName,
                true
        );
        MixEngine engine = new MixEngine(BUKKIT_LOGGER);
        MixOutput out;
        try {
            out = engine.run(input);
        } catch (IOException | RuntimeException e) {
            Logger.warn("Mix engine failed: " + e.getMessage());
            return;
        }
        if (shuttingDown()) return;

        // The merged Java zip is complete the instant the engine returns: rerouting re-zips into a
        // SEPARATE external folder and Bedrock conversion reads the unzipped mergedDir + writes
        // separate outputs — neither alters out.mergedZip(). So publish the Java pack and start
        // hosting NOW, before the (much slower, ~minute-long) Bedrock conversion runs. AutoHost.initialize()
        // schedules its work on an async task, so the Bedrock conversion below proceeds in parallel and
        // Java clients no longer wait on it.
        finalResourcePack = out.mergedZip();
        finalSHA1 = out.sha1Hex();
        finalSHA1Bytes = out.sha1Bytes();
        Logger.info("Java resource pack ready; starting hosting (Bedrock conversion continues in parallel).");
        AutoHost.initialize();

        // Reroute the merged zip (Bukkit-only because DefaultConfig is Bukkit-bound).
        applyResourcePackRerouting(out.mergedDir());

        // Bedrock conversion needs the still-extant unzipped folder, which the engine
        // intentionally leaves on disk for exactly this kind of post-processing.
        // The context wires the platform-neutral converter into DefaultConfig +
        // Bukkit.getPluginManager + GeyserDeployer; the converter's own gate
        // checks isBedrockConversionEnabled() so the outer if() here is belt-
        // and-braces (preserved to skip the context construction in the common
        // "Bedrock conversion disabled" case).
        if (DefaultConfig.isBedrockConversionEnabled()) {
            BedrockConversion.generate(out.mergedDir(), outputFolder, new BukkitBedrockConverterContext());
            // Java hosting started before this conversion, so AutoHost's recurring relay task
            // already fired (and skipped) before these files existed. Push them to the relay now
            // so a network-mode proxy sees this backend's Bedrock content without a ~25 min wait.
            AutoHost.publishBedrockOutputs();
        }
        if (shuttingDown()) return;

        // Final cleanup: the engine already deleted per-pack staging dirs but left the
        // merged unzipped folder so we could run rerouting + Bedrock conversion against
        // it. Now it's safe to delete. Bedrock outputs (zip + Geyser mappings) survive
        // because they live alongside, not inside, the merged folder.
        if (out.mergedDir().exists()) {
            recursivelyDeleteDirectory(out.mergedDir());
        }

        Logger.info("Resource pack mixing complete.");
    }

    // Bail out of the mix pipeline as soon as the plugin disables, so Bukkit
    // doesn't nag about un-shutdown async tasks. Each phase below can take
    // multiple seconds (file I/O on potentially many resource packs).
    private static boolean shuttingDown() {
        return com.magmaguy.magmacore.MagmaCore.isShutdownRequested(ResourcePackManager.plugin);
    }

    private static boolean initializeDefaultPluginFolders() {
        try {
            mixerFolder = new File(ResourcePackManager.plugin.getDataFolder().getAbsolutePath() + File.separatorChar + "mixer");
            if (!mixerFolder.exists()) mixerFolder.mkdir();

            File outputFolder = getOutputFolder();
            if (!outputFolder.exists()) outputFolder.mkdir();

            return true;
        } catch (Exception e) {
            Logger.warn("Failed to create default plugin folders! Check the OS permissions on the plugin's configuration folder.");
            return false;
        }
    }

    /**
     * Builds the ordered list of resource packs to merge, sorted by configured priority.
     * Packs are copied in this order during merging — higher priority packs go first,
     * and their files are preserved when lower priority packs collide with them.
     *
     * <p>Cluster-style raw directories in the mixer folder are appended strictly LAST
     * (after the priority-sorted zips), preserving legacy {@code copyClusterDirectoriesToOutput}
     * ordering. Lumping them into the same priority bucket as un-prioritized zips would
     * leave the relative order to {@code HashMap} iteration, which is JVM-undefined.</p>
     */
    private static void initializeThirdPartyResourcePacks() {
        List<String> priorityOrder = DefaultConfig.getPriorityOrder();

        // Collect all enabled packs from both the static set and API registrations
        Set<ThirdPartyResourcePack> uniquePacks = new LinkedHashSet<>(ThirdPartyResourcePack.thirdPartyResourcePacks);
        uniquePacks.addAll(ResourcePackManagerAPI.thirdPartyResourcePackHashMap.values());
        List<ThirdPartyResourcePack> allPacks = new ArrayList<>(uniquePacks);

        // Build a unified map of (file -> priority) for sorting — ZIP/file inputs only.
        Map<File, Integer> filePriorities = new HashMap<>();
        Set<String> registeredFilenames = new HashSet<>();

        for (ThirdPartyResourcePack pack : allPacks) {
            if (!pack.isEnabled() || pack.getMixerResourcePack() == null) continue;
            registeredFilenames.add(pack.getMixerResourcePack().getName());
            filePriorities.put(pack.getMixerResourcePack(),
                    pack.getPriority() >= 0 ? pack.getPriority() : Integer.MAX_VALUE);
        }

        // Cluster directories are kept in a separate ordered list so we can append them
        // strictly after the sorted zips — matches legacy "clusters always come last".
        List<File> clusterDirs = new ArrayList<>();

        // Add custom zip files from the mixer folder (user-provided packs not tied to a plugin)
        File[] mixerContents = mixerFolder.listFiles();
        if (mixerContents != null) {
            for (File file : mixerContents) {
                if (file.isDirectory() || !file.getName().endsWith(".zip")) continue;
                if (registeredFilenames.contains(file.getName())) continue;
                // Check priority list by both filename and name without .zip
                int prio = priorityOrder.indexOf(file.getName());
                if (prio < 0) prio = priorityOrder.indexOf(file.getName().replace(".zip", ""));
                filePriorities.put(file, prio >= 0 ? prio : Integer.MAX_VALUE);
            }

            // Surface cluster-style directories the same way the legacy pipeline did:
            // the engine handles directory inputs natively (staging them under a
            // cluster_<dirname> wrapper), so we just forward them in priority order.
            for (File file : mixerContents) {
                if (!file.isDirectory()) continue;
                if (file.getName().equals("output")) continue;
                clusterDirs.add(file);
            }
        }

        // Sort prioritized files (lower index = higher priority = copied first = wins collisions),
        // then append clusters in deterministic listFiles() order — they're always last so explicit
        // plugin-registered packs win collisions against them, exactly like the legacy pipeline.
        List<File> result = new ArrayList<>(filePriorities.size() + clusterDirs.size());
        filePriorities.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .forEach(entry -> result.add(entry.getKey()));
        result.addAll(clusterDirs);
        orderedFiles = result;
    }

    private static void applyResourcePackRerouting(File mergedDir) {
        String rerouteTarget = DefaultConfig.getResourcePackRerouting();
        if (rerouteTarget == null || rerouteTarget.isEmpty() || rerouteTarget.isBlank()) return;
        try {
            File rerouteFolder = new File(ResourcePackManager.plugin.getDataFolder().getParentFile().getAbsolutePath() + File.separatorChar + rerouteTarget);
            if (!rerouteFolder.exists()) {
                Logger.warn("Failed to reroute zipped file to " + rerouteFolder.getAbsolutePath() + " because that folder does not exist!");
                return;
            }
            if (!rerouteFolder.isDirectory()) {
                Logger.warn("Failed to reroute zipped file to " + rerouteFolder.getAbsolutePath() + " because that is a file and not a folder!");
                return;
            }
            // Re-zip from the unzipped merged dir into the reroute target. Using
            // MagmaCore's ZipFile here (not the resourcepackmanager-mixer ZipUtil) keeps the existing
            // dependency surface untouched — both implementations are byte-equivalent
            // since ZipUtil was ported from this exact class.
            if (!ZipFile.zip(mergedDir, rerouteFolder.getPath() + File.separatorChar + resourcePackName + ".zip")) {
                Logger.warn("Failed to zip merged resource pack into reroute directory!");
            }
        } catch (Exception e) {
            Logger.warn("Failed to reroute zipped file to " + DefaultConfig.getResourcePackRerouting());
        }
    }

    private static File getOutputFolder() {
        return new File(ResourcePackManager.plugin.getDataFolder().getAbsolutePath() + File.separatorChar + "output");
    }

    /**
     * Recursively delete a directory tree. Kept as a public static helper because
     * {@code ThirdPartyResourcePack} uses it when assembling cluster temp folders
     * — extracting it into {@code resourcepackmanager-mixer} would force {@code ThirdPartyResourcePack}
     * (a Bukkit-side class) to depend on the mixer internal API.
     */
    public static void recursivelyDeleteDirectory(File directory) {
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
                Logger.warn("Failed to delete directory " + directory.getPath());
            }
        } else {
            try {
                Files.delete(directory.toPath());
            } catch (IOException e) {
                Logger.warn("Failed to delete file " + directory.getPath());
            }
        }
    }

    /**
     * Recursively copy a directory tree with the same collision-resolution rules
     * the engine uses (pack.mcmeta merge, JSON deep-merge for mergeable files,
     * higher-priority wins for everything else). Used by
     * {@code ThirdPartyResourcePack.processCluster} to combine multiple cluster
     * sub-packs into a single temp directory before zipping it as one pack input
     * for the engine. Kept public for the same callsite reason — moving it into
     * {@code resourcepackmanager-mixer} would force the Bukkit-side cluster code to import the
     * mixer's internal helpers.
     */
    public static void recursivelyCopyDirectory(File source, File target) {
        if (source.isDirectory()) {
            String sourceName = source.getName();
            File nestedTarget = new File(target.getAbsolutePath() + File.separatorChar + sourceName);
            nestedTarget.mkdir();
            File[] children = source.listFiles();
            if (children != null) {
                for (File file : children) {
                    recursivelyCopyDirectory(file, nestedTarget);
                }
            }
        } else {
            try {
                Path targetPath = Path.of(target.getPath() + File.separatorChar + source.getName());
                if (targetPath.toFile().exists()) {
                    resolveClusterFileCollision(source, targetPath.toFile());
                    return;
                }
                Path parent = targetPath.getParent();
                if (parent != null) parent.toFile().mkdirs();
                Files.copy(source.toPath(), targetPath);
            } catch (IOException e) {
                Logger.warn("Failed to copy file");
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Collision resolution used during cluster pre-merging — same rules as the
     * engine's {@code resolveFileCollision}, but with no collision-log recording
     * (matches legacy behaviour where cluster-stage collisions were silently
     * dropped because {@code collisionLog} was still null at this point in the
     * lifecycle).
     */
    private static void resolveClusterFileCollision(File sourceFile, File targetFile) throws IOException {
        if (targetFile.getName().equals("pack.mcmeta")) {
            CLUSTER_MERGE.mergePackMcmeta(sourceFile, targetFile);
            return;
        }
        if (!targetFile.getName().endsWith(".json")) return;
        if (!CLUSTER_MERGE.isMergeableJsonFile(targetFile)) return;

        JsonObject json1 = CLUSTER_MERGE.readJsonFile(sourceFile);
        JsonObject json2 = CLUSTER_MERGE.readJsonFile(targetFile);
        if (json1 == null && json2 == null) return;
        if (json1 == null) return;
        if (json2 == null) {
            Files.copy(sourceFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return;
        }

        JsonObject mergedJson;
        if (CLUSTER_MERGE.isLegacyItemModel(targetFile)) {
            mergedJson = CLUSTER_MERGE.mergeLegacyItemModelOverrides(json1, json2);
        } else if (CLUSTER_MERGE.isItemsFile(targetFile)) {
            mergedJson = CLUSTER_MERGE.mergeItemsModels(json1, json2);
        } else if (targetFile.getName().equals("sounds.json")) {
            mergedJson = CLUSTER_MERGE.mergeSoundsJson(json1, json2);
        } else {
            mergedJson = CLUSTER_MERGE.mergeJsonObjects(json1, json2);
        }

        if (CLUSTER_MERGE.isLegacyItemModel(targetFile) && mergedJson.has("overrides")) {
            CLUSTER_MERGE.sortModelOverrides(mergedJson);
        }

        try (FileWriter writer = new FileWriter(targetFile)) {
            new Gson().toJson(mergedJson, writer);
        }
    }
}
