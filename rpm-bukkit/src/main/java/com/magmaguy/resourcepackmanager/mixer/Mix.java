package com.magmaguy.resourcepackmanager.mixer;

import com.google.gson.*;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.ZipFile;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.api.ResourcePackManagerAPI;
import com.magmaguy.resourcepackmanager.autohost.AutoHost;
import com.magmaguy.resourcepackmanager.bedrock.BedrockConversion;
import com.magmaguy.resourcepackmanager.config.DefaultConfig;
import com.magmaguy.resourcepackmanager.thirdparty.ThirdPartyResourcePack;
import com.magmaguy.resourcepackmanager.utils.SHA1Generator;
import lombok.Getter;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class Mix {
    private static final String resourcePackName = "ResourcePackManager_RSP";
    private static final com.magmaguy.rspm.mixer.MergeOperations merge =
            new com.magmaguy.rspm.mixer.MergeOperations(new com.magmaguy.rspm.mixer.MixerLogger() {
                @Override public void info(String m) { com.magmaguy.magmacore.util.Logger.info(m); }
                @Override public void warn(String m) { com.magmaguy.magmacore.util.Logger.warn(m); }
            });
    private static List<File> resourcePacks;
    private static List<String> orderedResourcePacks;
    @Getter
    private static File finalResourcePack;
    @Getter
    private static String finalSHA1;
    @Getter
    private static byte[] finalSHA1Bytes;
    private static File mixerFolder;
    private static List<String> collisionLog;

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
        collisionLog = new ArrayList<>();
        if (!initializeDefaultPluginFolders()) return;
        if (shuttingDown()) return;
        initializeThirdPartyResourcePacks();
        if (shuttingDown()) return;
        cloneToOutputAndUnzip();
        if (shuttingDown()) return;
        createOutputDefaultElements();
        if (shuttingDown()) return;
        writeCollisionLog();
        Logger.info("Resource pack mixing complete.");
        if (shuttingDown()) return;
        AutoHost.initialize();
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
     */
    private static void initializeThirdPartyResourcePacks() {
        orderedResourcePacks = new ArrayList<>();
        resourcePacks = new ArrayList<>();
        List<String> priorityOrder = DefaultConfig.getPriorityOrder();

        // Collect all enabled packs from both the static set and API registrations
        List<ThirdPartyResourcePack> allPacks = new ArrayList<>(ThirdPartyResourcePack.thirdPartyResourcePacks);
        allPacks.addAll(ResourcePackManagerAPI.thirdPartyResourcePackHashMap.values());

        // Build a unified map of (file -> priority) for sorting
        Map<File, Integer> filePriorities = new HashMap<>();
        Set<String> registeredFilenames = new HashSet<>();

        for (ThirdPartyResourcePack pack : allPacks) {
            if (!pack.isEnabled() || pack.getMixerResourcePack() == null) continue;
            registeredFilenames.add(pack.getMixerResourcePack().getName());
            filePriorities.put(pack.getMixerResourcePack(),
                    pack.getPriority() >= 0 ? pack.getPriority() : Integer.MAX_VALUE);
        }

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
        }

        // Sort all packs by priority (lower index = higher priority = copied first = wins collisions)
        filePriorities.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .forEach(entry -> {
                    resourcePacks.add(entry.getKey());
                    orderedResourcePacks.add(entry.getKey().getName().replace(".zip", ""));
                });
    }

    private static void cloneToOutputAndUnzip() {
        resourcePacks.forEach(resourcePack -> {
            try {
                if (resourcePack == null) {
                    Logger.warn("A resource pack was null by the time it was meant to be unzipped!");
                    return;
                }
                File outputDir = new File(ResourcePackManager.plugin.getDataFolder().getAbsolutePath() + File.separatorChar + "output" + File.separatorChar + resourcePack.getName().replace(".zip", ""));
                // Pre-create the output directory to ensure getCanonicalPath() works correctly in the unzip security check
                if (!outputDir.exists()) outputDir.mkdirs();
                ZipFile.unzip(resourcePack, outputDir);
            } catch (Exception e) {
                if (resourcePack == null)
                    Logger.warn("Failed to extract resource pack! The file might be encrypted. This pack will be skipped.");
                else {
                    Logger.warn("Failed to extract resource pack " + resourcePack.getName() + " - the file might be encrypted or the plugin distributes its own pack. This pack will be skipped.");
                    Logger.warn("Error details: " + e.getMessage());
                }
            }
        });

        // Also copy any directories from mixer folder (from cluster processing) directly to output
        copyClusterDirectoriesToOutput();
    }

    private static void copyClusterDirectoriesToOutput() {
        if (mixerFolder == null || !mixerFolder.exists()) return;
        File[] mixerContents = mixerFolder.listFiles();
        if (mixerContents == null) return;

        for (File file : mixerContents) {
            // Only process directories (cluster content like 'assets')
            if (!file.isDirectory()) continue;
            // Skip the output folder if it somehow ends up here
            if (file.getName().equals("output")) continue;

            // Copy the directory to a wrapper folder in output
            // This ensures the structure is: output/cluster_assets/assets/...
            File outputWrapper = new File(getOutputFolder().getPath() + File.separatorChar + "cluster_" + file.getName());
            if (!outputWrapper.exists()) outputWrapper.mkdir();

            try {
                recursivelyCopyDirectory(file, outputWrapper);
                // Track this for the merge process
                if (!orderedResourcePacks.contains("cluster_" + file.getName())) {
                    orderedResourcePacks.add("cluster_" + file.getName());
                }
            } catch (Exception e) {
                Logger.warn("Failed to copy cluster directory " + file.getName() + " to output folder");
                e.printStackTrace();
            }
        }
    }

    private static void createOutputDefaultElements() {
        //Clear old resource pack
        if (getOutputResourcePackFolder().exists()) {
            recursivelyDeleteDirectory(getOutputResourcePackFolder());
        }
        //Make sure new resource pack exists
        try {
            getOutputResourcePackFolder().mkdir();
        } catch (Exception e) {
            Logger.warn("Failed to create resource pack output directory");
            throw new RuntimeException(e);
        }

        List<File> orderedFiles = new ArrayList<>();
        for (String filename : orderedResourcePacks) {
            orderedFiles.add(new File(getOutputFolder().getPath() + File.separatorChar + filename));
        }

        for (File file : orderedFiles) {
            if (file.getName().equals(resourcePackName + ".zip")) continue;
            if (!file.exists()) {
                // Pack likely failed to extract (possibly encrypted) - skip gracefully
                continue;
            }
            if (!file.isDirectory()) {
                if (file.getName().endsWith(".zip")) continue;
                Logger.warn("Somehow a non-folder file made its way to the output folder! This isn't good. File: " + file.getAbsolutePath());
                continue;
            }
            File[] subFiles = file.listFiles();
            if (subFiles == null) continue;
            for (File subFile : subFiles) {
                recursivelyCopyDirectory(subFile, getOutputResourcePackFolder());
            }
        }

        // Merge base atlas sources into overlay atlas files so overlays don't shadow base entries.
        // When an overlay activates (e.g. ItemsAdder's ia_overlay_modern_atlas / ia_overlay_legacy_atlas),
        // its atlas file replaces the base — without this, sources defined only in the base are lost.
        merge.mergeBaseAtlasSourcesIntoOverlays(getOutputResourcePackFolder());

        if (!ZipFile.zip(getOutputResourcePackFolder(), getOutputResourcePackFolder().getPath() + ".zip")) {
            Logger.warn("Failed to zip merged resource pack!");
            return;
        }

        if (!DefaultConfig.getResourcePackRerouting().isEmpty() && !DefaultConfig.getResourcePackRerouting().isBlank()) {
            try {
                File rerouteFolder = new File(ResourcePackManager.plugin.getDataFolder().getParentFile().getAbsolutePath() + File.separatorChar + DefaultConfig.getResourcePackRerouting());
                if (!rerouteFolder.exists()) {
                    Logger.warn("Failed to reroute zipped file to " + rerouteFolder.getAbsolutePath() + " because that folder does not exist!");
                } else if (!rerouteFolder.isDirectory()) {
                    Logger.warn("Failed to reroute zipped file to " + rerouteFolder.getAbsolutePath() + " because that is a file and not a folder!");
                } else if (!ZipFile.zip(getOutputResourcePackFolder(), rerouteFolder.getPath() + File.separatorChar + resourcePackName + ".zip")) {
                    Logger.warn("Failed to zip merged resource pack into reroute directory!");
                    return;
                }
            } catch (Exception e) {
                Logger.warn("Failed to reroute zipped file to " + DefaultConfig.getResourcePackRerouting());
            }
        }

        // Generate Bedrock resource pack for GeyserMC
        if (DefaultConfig.isBedrockConversionEnabled()) {
            BedrockConversion.generate(getOutputResourcePackFolder(), getOutputFolder());
        }

        // Files in output/ that must be preserved across cleanup. The Bedrock conversion
        // outputs (zip + Geyser mappings) need to survive so deployPreviousIfNeeded() can
        // pre-deploy them on the next startup — otherwise Geyser registers items from
        // stale in-place mappings before the new regen finishes, and the on-disk pack /
        // mapping mismatch causes invisible custom items on Bedrock for the whole session.
        java.util.Set<String> preservedOutputs = java.util.Set.of(
                resourcePackName + ".zip",
                "ResourcePackManager_Bedrock.zip",
                "rspm_geyser_mappings.json"
        );
        for (File file : getOutputFolder().listFiles()) {
            if (file.getName().equals(resourcePackName + ".zip")) {
                finalResourcePack = file;
                try {
                    finalSHA1 = SHA1Generator.sha1CodeString(finalResourcePack);
                    finalSHA1Bytes = SHA1Generator.sha1CodeByteArray(finalResourcePack);
                } catch (Exception e) {
                    Logger.warn("Failed to get SHA1 from zipped resource pack!");
                    finalResourcePack = null;
                }
                continue;
            }
            if (preservedOutputs.contains(file.getName())) continue;
            recursivelyDeleteDirectory(file);
        }

    }

    private static File getOutputFolder() {
        return new File(ResourcePackManager.plugin.getDataFolder().getAbsolutePath() + File.separatorChar + "output");
    }

    private static File getOutputResourcePackFolder() {
        return new File(getOutputFolder().getAbsolutePath() + File.separatorChar + resourcePackName);
    }

    public static void recursivelyDeleteDirectory(File directory) {
        if (directory.isDirectory()) {
            for (File file : directory.listFiles()) {
                recursivelyDeleteDirectory(file);
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

    public static void recursivelyCopyDirectory(File source, File target) {
        if (source.isDirectory()) {
            String sourceName = source.getName();

            target = new File(target.getAbsolutePath() + File.separatorChar + sourceName);
            target.mkdir();
            for (File file : source.listFiles()) {
                recursivelyCopyDirectory(file, target);
            }
        } else {
            try {
                if (Path.of(target.getPath() + File.separatorChar + source.getName()).toFile().exists()) {
                    resolveFileCollision(source, Path.of(target.getPath() + File.separatorChar + source.getName()).toFile());
                    return;
                }
                Path targetPath = Path.of(target.getPath() + File.separatorChar + source.getName());
                targetPath.getParent().toFile().mkdirs();
                Files.copy(source.toPath(), targetPath);
            } catch (IOException e) {
                Logger.warn("Failed to copy file");
                throw new RuntimeException(e);
            }
        }
    }

    public static void resolveFileCollision(File sourceFile, File targetFile) throws IOException {
        // pack.mcmeta needs overlay entries merged from all packs
        if (targetFile.getName().equals("pack.mcmeta")) {
            merge.mergePackMcmeta(sourceFile, targetFile);
            logCollision("Merged pack.mcmeta: " + targetFile.getPath());
            return;
        }

        if (!targetFile.getName().endsWith(".json")) {
            // Non-JSON: higher priority pack already placed this file, keep it
            logCollision("Kept (higher priority): " + targetFile.getPath());
            return;
        }

        if (!merge.isMergeableJsonFile(targetFile)) {
            // Non-mergeable JSON (models, blockstates, etc.): higher priority version takes precedence
            logCollision("Kept (higher priority, non-mergeable JSON): " + targetFile.getPath());
            return;
        }

        JsonObject json1 = merge.readJsonFile(sourceFile);
        JsonObject json2 = merge.readJsonFile(targetFile);

        if (json1 == null && json2 == null) {
            Logger.warn("Both JSON files unreadable during merge, skipping: " + targetFile.getPath());
            return;
        }
        if (json1 == null) return;
        if (json2 == null) {
            Files.copy(sourceFile.toPath(), targetFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            logCollision("Replaced (unreadable target JSON): " + targetFile.getPath());
            return;
        }

        // Route to format-specific merge where needed
        JsonObject mergedJson;
        if (merge.isItemsFile(targetFile)) {
            mergedJson = merge.mergeItemsModels(json1, json2);
        } else if (targetFile.getName().equals("sounds.json")) {
            mergedJson = merge.mergeSoundsJson(json1, json2);
        } else {
            mergedJson = merge.mergeJsonObjects(json1, json2);
        }

        // Post-process: sort overrides in legacy item model files by custom_model_data
        if (merge.isLegacyItemModel(targetFile) && mergedJson.has("overrides")) {
            merge.sortModelOverrides(mergedJson);
        }

        try (FileWriter writer = new FileWriter(targetFile)) {
            new Gson().toJson(mergedJson, writer);
        }

        logCollision("Merged: " + targetFile.getPath());
    }

    /**
     * Writes the collision log to a file in the plugin's config folder.
     * Only keeps the latest log, no history.
     */
    private static void writeCollisionLog() {
        if (collisionLog == null || collisionLog.isEmpty()) return;

        File logFile = new File(ResourcePackManager.plugin.getDataFolder().getAbsolutePath() + File.separatorChar + "collision_log.txt");
        try (FileWriter writer = new FileWriter(logFile, false)) {
            writer.write("Resource Pack Collision Log\n");
            writer.write("Generated: " + java.time.LocalDateTime.now() + "\n");
            writer.write("================================================\n\n");
            for (String entry : collisionLog) {
                writer.write(entry + "\n");
            }
        } catch (IOException e) {
            Logger.warn("Failed to write collision log file.");
        }
    }

    private static void logCollision(String message) {
        if (collisionLog != null) {
            collisionLog.add(message);
        }
    }

}

