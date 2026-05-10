package com.magmaguy.resourcepackmanager.mixer;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
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
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class Mix {
    private static final String resourcePackName = "ResourcePackManager_RSP";
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

        // Merge base atlas sources into overlay atlas files so overlays don't shadow base entries
        mergeBaseAtlasSourcesIntoOverlays(getOutputResourcePackFolder());

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
            mergePackMcmeta(sourceFile, targetFile);
            return;
        }

        if (!targetFile.getName().endsWith(".json")) {
            // Non-JSON: higher priority pack already placed this file, keep it
            logCollision("Kept (higher priority): " + targetFile.getPath());
            return;
        }

        if (!isMergeableJsonFile(targetFile)) {
            // Non-mergeable JSON (models, blockstates, etc.): higher priority version takes precedence
            logCollision("Kept (higher priority, non-mergeable JSON): " + targetFile.getPath());
            return;
        }

        JsonObject json1 = readJsonFile(sourceFile);
        JsonObject json2 = readJsonFile(targetFile);

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
        if (isItemsFile(targetFile)) {
            mergedJson = mergeItemsModels(json1, json2);
        } else if (targetFile.getName().equals("sounds.json")) {
            mergedJson = mergeSoundsJson(json1, json2);
        } else {
            mergedJson = mergeJsonObjects(json1, json2);
        }

        // Post-process: sort overrides in legacy item model files by custom_model_data
        if (isLegacyItemModel(targetFile) && mergedJson.has("overrides")) {
            sortModelOverrides(mergedJson);
        }

        try (FileWriter writer = new FileWriter(targetFile)) {
            new Gson().toJson(mergedJson, writer);
        }

        logCollision("Merged: " + targetFile.getPath());
    }

    private static JsonObject readJsonFile(File file) {
        try (FileReader reader = new FileReader(file)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            Logger.warn("Malformed JSON: " + file.getAbsolutePath());
            try (FileReader reader = new FileReader(file);
                 JsonReader jsonReader = new JsonReader(reader)) {
                jsonReader.setStrictness(Strictness.LENIENT);
                return JsonParser.parseReader(jsonReader).getAsJsonObject();
            } catch (Exception ex) {
                Logger.warn("Unreadable JSON: " + file.getAbsolutePath());
                return null;
            }
        }
    }

    /**
     * Checks if a JSON file is designed to be merged (content can be combined).
     * Files like sounds.json, lang files, atlases, fonts, and vanilla item model overrides can be merged.
     * Custom model files, blockstates, equipment layers, etc. have fixed-size arrays that break when concatenated.
     */
    private static boolean isMergeableJsonFile(File file) {
        String path = file.getPath().replace("\\", "/");
        String fileName = file.getName();

        // sounds.json files should be merged
        if (fileName.equals("sounds.json")) {
            return true;
        }

        // Language files should be merged
        if (path.contains("/lang/") || path.contains("/languages/")) {
            return true;
        }

        // Vanilla item model overrides should be merged (for custom model data)
        if (path.contains("/minecraft/models/item/")) {
            return true;
        }

        // Atlas files should be merged (sources array)
        if (path.contains("/atlases/")) {
            return true;
        }

        // Font files should be merged (providers array)
        if (path.contains("/font/")) {
            return true;
        }

        // 1.21.4+ item model definitions should be merged (range_dispatch entries, select cases)
        if (path.contains("/items/")) {
            return true;
        }

        // All other JSON files (custom models, blockstates, equipment layers, etc.) should not be merged
        return false;
    }

    public static JsonObject mergeJsonObjects(JsonObject json1, JsonObject json2) {
        JsonObject mergedJson = new JsonObject();

        for (String key : json1.keySet()) {
            if (json2.has(key)) {
                JsonElement value1 = json1.get(key);
                JsonElement value2 = json2.get(key);
                if (value1.isJsonObject() && value2.isJsonObject()) {
                    mergedJson.add(key, mergeJsonObjects(value1.getAsJsonObject(), value2.getAsJsonObject()));
                } else if (value1.isJsonArray() && value2.isJsonArray()) {
                    mergedJson.add(key, mergeJsonArrays(value1.getAsJsonArray(), value2.getAsJsonArray()));
                } else {
                    mergedJson.add(key, value2); // Override with the value from json2
                }
            } else {
                mergedJson.add(key, json1.get(key));
            }
        }

        for (String key : json2.keySet()) {
            if (!json1.has(key)) {
                mergedJson.add(key, json2.get(key));
            }
        }

        return mergedJson;
    }

    private static JsonArray mergeJsonArrays(JsonArray array1, JsonArray array2) {
        JsonArray mergedArray = new JsonArray();

        for (JsonElement element : array1) {
            mergedArray.add(element);
        }

        for (JsonElement element : array2) {
            mergedArray.add(element);
        }

        return mergedArray;
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

    /**
     * After all packs are merged, overlay directories may contain atlas files that shadow the base atlas.
     * When Minecraft activates an overlay, its atlas file replaces the base — so any sources defined only
     * in the base are lost. This method copies base atlas sources into each overlay atlas file to prevent that.
     */
    private static void mergeBaseAtlasSourcesIntoOverlays(File resourcePackRoot) {
        File packMcmeta = new File(resourcePackRoot, "pack.mcmeta");
        if (!packMcmeta.exists()) return;

        JsonObject mcmeta = readJsonFile(packMcmeta);
        if (mcmeta == null || !mcmeta.has("overlays")) return;

        JsonObject overlays = mcmeta.getAsJsonObject("overlays");
        if (!overlays.has("entries")) return;

        for (JsonElement entry : overlays.getAsJsonArray("entries")) {
            if (!entry.isJsonObject()) continue;
            JsonObject overlayEntry = entry.getAsJsonObject();
            if (!overlayEntry.has("directory")) continue;
            String overlayDir = overlayEntry.get("directory").getAsString();

            File overlayRoot = new File(resourcePackRoot, overlayDir);
            if (!overlayRoot.exists() || !overlayRoot.isDirectory()) continue;

            // Find all atlas files in this overlay and merge base sources into them
            mergeBaseAtlasesForOverlay(resourcePackRoot, overlayRoot);
        }
    }

    private static void mergeBaseAtlasesForOverlay(File resourcePackRoot, File overlayRoot) {
        // Scan for atlas files under the overlay: <overlay>/assets/*/atlases/*.json
        File overlayAssets = new File(overlayRoot, "assets");
        if (!overlayAssets.exists() || !overlayAssets.isDirectory()) return;

        File[] namespaces = overlayAssets.listFiles(File::isDirectory);
        if (namespaces == null) return;

        for (File namespace : namespaces) {
            File atlasesDir = new File(namespace, "atlases");
            if (!atlasesDir.exists() || !atlasesDir.isDirectory()) continue;

            File[] atlasFiles = atlasesDir.listFiles((dir, name) -> name.endsWith(".json"));
            if (atlasFiles == null) continue;

            for (File overlayAtlas : atlasFiles) {
                // Find the corresponding base atlas: assets/<namespace>/atlases/<name>.json
                String relativePath = "assets" + File.separatorChar + namespace.getName()
                        + File.separatorChar + "atlases" + File.separatorChar + overlayAtlas.getName();
                File baseAtlas = new File(resourcePackRoot, relativePath);

                if (!baseAtlas.exists()) continue;

                mergeBaseSourcesIntoOverlayAtlas(baseAtlas, overlayAtlas);
            }
        }
    }

    private static void mergeBaseSourcesIntoOverlayAtlas(File baseAtlas, File overlayAtlas) {
        JsonObject baseJson = readJsonFile(baseAtlas);
        JsonObject overlayJson = readJsonFile(overlayAtlas);

        if (baseJson == null || overlayJson == null) return;
        if (!baseJson.has("sources") || !overlayJson.has("sources")) return;

        JsonArray baseSources = baseJson.getAsJsonArray("sources");
        JsonArray overlaySources = overlayJson.getAsJsonArray("sources");

        // Build a set of source signatures already in the overlay to avoid duplicates
        Set<String> existingSignatures = new HashSet<>();
        for (JsonElement e : overlaySources) {
            existingSignatures.add(e.toString());
        }

        // Prepend base sources that aren't already in the overlay
        JsonArray merged = new JsonArray();
        int addedCount = 0;
        for (JsonElement baseSource : baseSources) {
            if (!existingSignatures.contains(baseSource.toString())) {
                merged.add(baseSource);
                addedCount++;
            }
        }

        // Append all existing overlay sources after the base sources
        for (JsonElement overlaySource : overlaySources) {
            merged.add(overlaySource);
        }

        if (addedCount == 0) return;

        overlayJson.add("sources", merged);

        try (FileWriter writer = new FileWriter(overlayAtlas)) {
            new Gson().toJson(overlayJson, writer);
        } catch (IOException e) {
            Logger.warn("Failed to merge base atlas sources into overlay atlas: " + overlayAtlas.getPath());
        }

        logCollision("Merged base atlas sources into overlay: " + overlayAtlas.getPath()
                + " (" + addedCount + " sources added from base)");
    }

    private static void mergePackMcmeta(File sourceFile, File targetFile) throws IOException {
        JsonObject source = readJsonFile(sourceFile);
        JsonObject target = readJsonFile(targetFile);

        if (source == null) return;
        if (target == null) {
            Files.copy(sourceFile.toPath(), targetFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return;
        }

        // Take the highest pack_format
        if (source.has("pack") && target.has("pack")) {
            JsonObject sourcePack = source.getAsJsonObject("pack");
            JsonObject targetPack = target.getAsJsonObject("pack");
            if (sourcePack.has("pack_format") && targetPack.has("pack_format")) {
                int sourceFormat = sourcePack.get("pack_format").getAsInt();
                int targetFormat = targetPack.get("pack_format").getAsInt();
                targetPack.addProperty("pack_format", Math.max(sourceFormat, targetFormat));
            }
        }

        // Merge supported_formats to widest range
        if (source.has("supported_formats") && target.has("supported_formats")) {
            JsonArray sourceFormats = source.getAsJsonArray("supported_formats");
            JsonArray targetFormats = target.getAsJsonArray("supported_formats");
            if (sourceFormats.size() >= 2 && targetFormats.size() >= 2) {
                int min = Math.min(sourceFormats.get(0).getAsInt(), targetFormats.get(0).getAsInt());
                int max = Math.max(sourceFormats.get(1).getAsInt(), targetFormats.get(1).getAsInt());
                JsonArray merged = new JsonArray();
                merged.add(min);
                merged.add(max);
                target.add("supported_formats", merged);
            }
        } else if (source.has("supported_formats") && !target.has("supported_formats")) {
            target.add("supported_formats", source.get("supported_formats"));
        }

        // Merge overlay entries from both packs
        JsonArray mergedEntries = new JsonArray();

        if (target.has("overlays")) {
            JsonObject targetOverlays = target.getAsJsonObject("overlays");
            if (targetOverlays.has("entries")) {
                mergedEntries.addAll(targetOverlays.getAsJsonArray("entries"));
            }
        }
        if (source.has("overlays")) {
            JsonObject sourceOverlays = source.getAsJsonObject("overlays");
            if (sourceOverlays.has("entries")) {
                Set<String> existingDirs = new HashSet<>();
                for (JsonElement e : mergedEntries) {
                    if (e.isJsonObject() && e.getAsJsonObject().has("directory")) {
                        existingDirs.add(e.getAsJsonObject().get("directory").getAsString());
                    }
                }
                for (JsonElement e : sourceOverlays.getAsJsonArray("entries")) {
                    if (e.isJsonObject()) {
                        String dir = e.getAsJsonObject().has("directory")
                                ? e.getAsJsonObject().get("directory").getAsString() : "";
                        if (!existingDirs.contains(dir)) {
                            mergedEntries.add(e);
                        }
                    }
                }
            }
        }

        if (mergedEntries.size() > 0) {
            // Defensive normalization: ensure overlay entries have min_format/max_format fields.
            // Starting with resource pack format 65 (Minecraft 1.21.9+), overlay entries MUST include
            // min_format and max_format as separate fields — the old "formats" field alone is no longer
            // sufficient. If an overlay's format range covers 65+, the client rejects entries missing
            // these fields with: "declares support for version newer than 64, but is missing mandatory
            // fields min_format and max_format".
            // This is NOT an RSPM bug — the source packs (e.g. ModelEngine) are generating overlay entries
            // without these fields. Ideally those packs should fix their own pack.mcmeta output.
            // We patch it here because RSPM gets the bug reports when the merged pack fails to load.
            normalizeOverlayEntries(mergedEntries);

            JsonObject overlays = new JsonObject();
            overlays.add("entries", mergedEntries);
            target.add("overlays", overlays);
        }

        // Preserve any non-standard top-level keys from source (e.g. "sodium" with ignored_shaders)
        for (String key : source.keySet()) {
            if (!target.has(key)) {
                target.add(key, source.get(key));
            }
        }

        try (FileWriter writer = new FileWriter(targetFile)) {
            new Gson().toJson(target, writer);
        }

        logCollision("Merged pack.mcmeta: " + targetFile.getPath());
    }

    /**
     * Patches overlay entries that are missing min_format/max_format fields.
     * See comment at call site for full rationale — this works around third-party packs
     * that haven't updated their pack.mcmeta to the 1.21.9+ overlay format.
     */
    private static void normalizeOverlayEntries(JsonArray entries) {
        for (JsonElement element : entries) {
            if (!element.isJsonObject()) continue;
            JsonObject entry = element.getAsJsonObject();
            if (entry.has("min_format") && entry.has("max_format")) continue;
            if (!entry.has("formats")) continue;

            int min, max;
            JsonElement formats = entry.get("formats");
            if (formats.isJsonArray()) {
                JsonArray arr = formats.getAsJsonArray();
                if (arr.size() < 2) continue;
                min = arr.get(0).getAsInt();
                max = arr.get(1).getAsInt();
            } else if (formats.isJsonObject()) {
                JsonObject obj = formats.getAsJsonObject();
                if (!obj.has("min_inclusive") || !obj.has("max_inclusive")) continue;
                min = obj.get("min_inclusive").getAsInt();
                max = obj.get("max_inclusive").getAsInt();
            } else if (formats.isJsonPrimitive()) {
                min = max = formats.getAsInt();
            } else {
                continue;
            }

            if (!entry.has("min_format")) entry.addProperty("min_format", min);
            if (!entry.has("max_format")) entry.addProperty("max_format", max);
        }
    }

    private static boolean isLegacyItemModel(File file) {
        return file.getPath().replace("\\", "/").contains("/minecraft/models/item/");
    }

    private static boolean isItemsFile(File file) {
        String path = file.getPath().replace("\\", "/");
        return path.contains("/items/") && !path.contains("/models/item/");
    }

    private static void sortModelOverrides(JsonObject modelJson) {
        JsonArray overrides = modelJson.getAsJsonArray("overrides");
        if (overrides == null || overrides.size() <= 1) return;

        List<JsonElement> sorted = new ArrayList<>();
        for (JsonElement e : overrides) sorted.add(e);

        sorted.sort((a, b) -> {
            int cmdA = getCustomModelData(a);
            int cmdB = getCustomModelData(b);
            return Integer.compare(cmdA, cmdB);
        });

        JsonArray sortedArray = new JsonArray();
        for (JsonElement e : sorted) sortedArray.add(e);
        modelJson.add("overrides", sortedArray);
    }

    private static int getCustomModelData(JsonElement override) {
        try {
            return override.getAsJsonObject()
                    .getAsJsonObject("predicate")
                    .get("custom_model_data").getAsInt();
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    private static JsonObject mergeItemsModels(JsonObject source, JsonObject target) {
        if (!source.has("model") || !target.has("model")) {
            return mergeJsonObjects(source, target);
        }

        JsonObject sourceModel = source.getAsJsonObject("model");
        JsonObject targetModel = target.getAsJsonObject("model");

        String sourceType = sourceModel.has("type") ? sourceModel.get("type").getAsString().replace("minecraft:", "") : "";
        String targetType = targetModel.has("type") ? targetModel.get("type").getAsString().replace("minecraft:", "") : "";

        if (sourceType.equals("range_dispatch") && targetType.equals("range_dispatch")) {
            mergeRangeDispatchEntries(sourceModel, targetModel);
            target.add("model", targetModel);
            for (String key : source.keySet()) {
                if (!key.equals("model") && !target.has(key)) {
                    target.add(key, source.get(key));
                }
            }
            return target;
        }

        if (sourceType.equals("select") && targetType.equals("select")) {
            String sourceProp = sourceModel.has("property") ? sourceModel.get("property").getAsString() : "";
            String targetProp = targetModel.has("property") ? targetModel.get("property").getAsString() : "";
            if (sourceProp.equals(targetProp)) {
                mergeSelectCases(sourceModel, targetModel);
                target.add("model", targetModel);
                for (String key : source.keySet()) {
                    if (!key.equals("model") && !target.has(key)) {
                        target.add(key, source.get(key));
                    }
                }
                return target;
            }
        }

        // Incompatible types: higher priority (target) wins
        return mergeJsonObjects(source, target);
    }

    private static void mergeRangeDispatchEntries(JsonObject sourceModel, JsonObject targetModel) {
        JsonArray sourceEntries = sourceModel.has("entries") ? sourceModel.getAsJsonArray("entries") : new JsonArray();
        JsonArray targetEntries = targetModel.has("entries") ? targetModel.getAsJsonArray("entries") : new JsonArray();

        // Collect all entries, target (higher priority) wins on threshold conflicts
        Map<Double, JsonElement> entryMap = new LinkedHashMap<>();
        for (JsonElement e : sourceEntries) {
            double threshold = e.getAsJsonObject().has("threshold")
                    ? e.getAsJsonObject().get("threshold").getAsDouble() : 0;
            entryMap.put(threshold, e);
        }
        for (JsonElement e : targetEntries) {
            double threshold = e.getAsJsonObject().has("threshold")
                    ? e.getAsJsonObject().get("threshold").getAsDouble() : 0;
            entryMap.put(threshold, e);
        }

        List<Map.Entry<Double, JsonElement>> sorted = new ArrayList<>(entryMap.entrySet());
        sorted.sort(Comparator.comparingDouble(Map.Entry::getKey));

        JsonArray merged = new JsonArray();
        for (Map.Entry<Double, JsonElement> entry : sorted) {
            merged.add(entry.getValue());
        }

        targetModel.add("entries", merged);
    }

    private static void mergeSelectCases(JsonObject sourceModel, JsonObject targetModel) {
        JsonArray sourceCases = sourceModel.has("cases") ? sourceModel.getAsJsonArray("cases") : new JsonArray();
        JsonArray targetCases = targetModel.has("cases") ? targetModel.getAsJsonArray("cases") : new JsonArray();

        Map<String, JsonElement> caseMap = new LinkedHashMap<>();
        for (JsonElement e : sourceCases) {
            String when = e.getAsJsonObject().has("when")
                    ? e.getAsJsonObject().get("when").getAsString() : "";
            caseMap.put(when, e);
        }
        for (JsonElement e : targetCases) {
            String when = e.getAsJsonObject().has("when")
                    ? e.getAsJsonObject().get("when").getAsString() : "";
            caseMap.put(when, e);
        }

        JsonArray merged = new JsonArray();
        for (JsonElement e : caseMap.values()) {
            merged.add(e);
        }

        targetModel.add("cases", merged);
    }

    private static JsonObject mergeSoundsJson(JsonObject source, JsonObject target) {
        JsonObject merged = new JsonObject();

        // Start with all source (lower priority) events
        for (String key : source.keySet()) {
            merged.add(key, source.get(key));
        }

        // Apply target (higher priority) events
        for (String key : target.keySet()) {
            JsonElement targetEvent = target.get(key);
            if (!merged.has(key)) {
                merged.add(key, targetEvent);
                continue;
            }

            if (targetEvent.isJsonObject()) {
                JsonObject targetObj = targetEvent.getAsJsonObject();
                boolean replace = targetObj.has("replace") && targetObj.get("replace").getAsBoolean();

                if (replace) {
                    merged.add(key, targetEvent);
                } else {
                    JsonObject sourceObj = merged.get(key).isJsonObject()
                            ? merged.get(key).getAsJsonObject() : new JsonObject();
                    JsonObject mergedEvent = new JsonObject();

                    JsonArray mergedSounds = new JsonArray();
                    if (sourceObj.has("sounds")) {
                        mergedSounds.addAll(sourceObj.getAsJsonArray("sounds"));
                    }
                    if (targetObj.has("sounds")) {
                        mergedSounds.addAll(targetObj.getAsJsonArray("sounds"));
                    }
                    mergedEvent.add("sounds", mergedSounds);

                    for (String prop : sourceObj.keySet()) {
                        if (!prop.equals("sounds") && !prop.equals("replace")) {
                            mergedEvent.add(prop, sourceObj.get(prop));
                        }
                    }
                    for (String prop : targetObj.keySet()) {
                        if (!prop.equals("sounds") && !prop.equals("replace")) {
                            mergedEvent.add(prop, targetObj.get(prop));
                        }
                    }

                    merged.add(key, mergedEvent);
                }
            } else {
                merged.add(key, targetEvent);
            }
        }

        return merged;
    }

}

