package com.magmaguy.resourcepackmanager.mixer;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.ZipFile;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.api.ResourcePackManagerAPI;
import com.magmaguy.resourcepackmanager.autohost.AutoHost;
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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

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
        initializeThirdPartyResourcePacks();
        cloneToOutputAndUnzip();
        createOutputDefaultElements();
        writeCollisionLog();
        Logger.info("Resource pack mixing complete.");
        AutoHost.initialize();
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

    private static void initializeThirdPartyResourcePacks() {
        orderedResourcePacks = new ArrayList<>();

        // Use a mutable list so we can addAll() to it
        List<ThirdPartyResourcePack> resourcePackManagers = new ArrayList<>(ThirdPartyResourcePack.thirdPartyResourcePacks.stream().toList());

        // Add any API-registered packs
        resourcePackManagers.addAll(ResourcePackManagerAPI.thirdPartyResourcePackHashMap.values());

        // Filter enabled packs into a working list
        List<ThirdPartyResourcePack> tempList = new ArrayList<>();
        resourcePackManagers.stream()
                .filter(ThirdPartyResourcePack::isEnabled)
                .forEach(tempList::add);

        resourcePacks = new ArrayList<>();
        List<File> customFiles = new ArrayList<>();
        List<File> thirdPartyFiles = new ArrayList<>();

        // Collect actual pack files from each enabled pack
        tempList.forEach(rsp -> {
            File mixerPack = rsp.getMixerResourcePack();
            if (mixerPack != null) {
                thirdPartyFiles.add(mixerPack);
            }
        });

        // Separate out truly custom files in the mixer folder
        for (File file : mixerFolder.listFiles()) {
            // Skip directories that aren't zip files - these are likely from cluster processing
            if (file.isDirectory()) continue;
            // Skip non-zip files
            if (!file.getName().endsWith(".zip")) continue;
            boolean isThirdParty = thirdPartyFiles.stream()
                    .anyMatch(tp -> tp.getName().equals(file.getName()));
            if (!isThirdParty) {
                customFiles.add(file);
            }
        }

        // Add in order of configured priority
        List<String> priorityOrder = DefaultConfig.getPriorityOrder();
        for (int i = 0; i < priorityOrder.size(); i++) {
            boolean foundAtThisPriority = false;

            Iterator<ThirdPartyResourcePack> it = tempList.iterator();
            while (it.hasNext()) {
                ThirdPartyResourcePack pack = it.next();
                if (pack.getPriority() == i) {
                    File f = pack.getMixerResourcePack();
                    if (f != null) {
                        resourcePacks.add(f);
                        orderedResourcePacks.add(f.getName().replace(".zip", ""));
                        it.remove();
                        foundAtThisPriority = true;
                        break;
                    }
                }
            }

            if (!foundAtThisPriority) {
                Iterator<File> fileIt = customFiles.iterator();
                while (fileIt.hasNext()) {
                    File custom = fileIt.next();
                    int prio = priorityOrder.indexOf(custom.getName());
                    if (prio == i) {
                        resourcePacks.add(custom);
                        orderedResourcePacks.add(custom.getName().replace(".zip", ""));
                        fileIt.remove();
                    }
                }
            }
        }

        // Any remaining third-party packs without explicit priority
        tempList.stream()
                .map(ThirdPartyResourcePack::getMixerResourcePack)
                .filter(Objects::nonNull)
                .forEach(packFile -> {
                    resourcePacks.add(packFile);
                    orderedResourcePacks.add(packFile.getName().replace(".zip", ""));
                });

        // Any leftover custom files without explicit priority
        customFiles.forEach(customFile -> {
            resourcePacks.add(customFile);
            orderedResourcePacks.add(customFile.getName().replace(".zip", ""));
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
                stripDirectoryMetadata(outputDir);
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
                recursivelyCopyDirectoryForCluster(file, new File(outputWrapper.getPath() + File.separatorChar + file.getName()));
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

    private static void recursivelyCopyDirectoryForCluster(File source, File target) {
        if (source.isDirectory()) {
            String sourceName = source.getName();

            // Skip shaders folder entirely
            if (sourceName.equals("shaders")) {
                return;
            }

            // Skip overlay directories
            if (sourceName.startsWith("ia_overlay") || sourceName.contains("overlay")) {
                return;
            }

            if (!target.exists()) target.mkdir();
            File[] files = source.listFiles();
            if (files != null) {
                for (File child : files) {
                    recursivelyCopyDirectoryForCluster(child, new File(target.getPath() + File.separatorChar + child.getName()));
                }
            }
        } else {
            try {
                // Check if target exists - if so, use resolveFileCollision to handle merging
                if (target.exists()) {
                    resolveFileCollision(source, target);
                } else {
                    Files.copy(source.toPath(), target.toPath());
                }
            } catch (IOException e) {
                Logger.warn("Failed to copy cluster file " + source.getPath());
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


        // Remove incompatible custom shaders that can break the resource pack
        removeIncompatibleShaders();

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

//        // ADD THE BEDROCK CONVERSION CALL HERE - AFTER finalResourcePack IS SET:
//            generateBedrockResourcePack();
    }

    private static File getOutputFolder() {
        return new File(ResourcePackManager.plugin.getDataFolder().getAbsolutePath() + File.separatorChar + "output");
    }

    private static File getOutputResourcePackFolder() {
        return new File(getOutputFolder().getAbsolutePath() + File.separatorChar + resourcePackName);
    }

    private static void recursivelyDeleteDirectory(File directory) {
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

    private static void recursivelyCopyDirectory(File source, File target) {
        if (source.isDirectory()) {
            String sourceName = source.getName();

            // Skip shaders folder entirely - ItemsAdder has incomplete shaders that break MC 1.21.4+
            if (sourceName.equals("shaders")) {
                return;
            }

            // Skip overlay directories - they contain version-specific partial assets
            if (sourceName.startsWith("ia_overlay") || sourceName.contains("overlay")) {
                return;
            }

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
                Files.copy(source.toPath(), Path.of(target.getPath() + File.separatorChar + source.getName()));
            } catch (IOException e) {
                Logger.warn("Failed to copy file");
                throw new RuntimeException(e);
            }
        }
    }

    public static void resolveFileCollision(File sourceFile, File targetFile) throws IOException {
        if (!targetFile.getName().endsWith(".json")) {
            //If the file isn't .json then it can't be merged, only replaced (such as with .png, shaders, etc).
            //Higher priority pack overwrites the file
            Files.copy(sourceFile.toPath(), targetFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            logCollision("Replaced: " + targetFile.getPath());
            return;
        }

        // Only merge JSON files that are designed to be merged (sounds.json, lang files)
        // Model files, blockstates, etc. have fixed-size arrays that break when concatenated
        if (!isMergeableJsonFile(targetFile)) {
            Files.copy(sourceFile.toPath(), targetFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            logCollision("Replaced (non-mergeable JSON): " + targetFile.getPath());
            return;
        }

        FileReader sourceFileReader = new FileReader(sourceFile);
        FileReader targetFileReader = new FileReader(targetFile);

        JsonObject json1 = null;
        // Read JSON files
        try {
            json1 = JsonParser.parseReader(sourceFileReader).getAsJsonObject();
        } catch (Exception e) {
            Logger.warn("Malformed JSON: " + sourceFile.getAbsolutePath());
            try {
                JsonReader jsonReader = new JsonReader(sourceFileReader);
                jsonReader.setStrictness(Strictness.LENIENT);
                json1 = JsonParser.parseReader(jsonReader).getAsJsonObject();
            } catch (Exception ex) {
                Logger.warn("Unreadable JSON: " + sourceFile.getAbsolutePath());
            }
        }
        JsonObject json2 = JsonParser.parseReader(targetFileReader).getAsJsonObject();

        sourceFileReader.close();
        targetFileReader.close();

        // Merge JSON objects
        JsonObject mergedJson = mergeJsonObjects(json1, json2);

        FileWriter targetFileWriter = new FileWriter(targetFile);

        // Write merged JSON to a file
        try (FileWriter file = targetFileWriter) {
            new Gson().toJson(mergedJson, file);
        }

        targetFileWriter.close();

        logCollision("Merged: " + targetFile.getPath());
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

        // All other JSON files (custom models, blockstates, equipment layers, etc.) should not be merged
        return false;
    }

    private static void stripDirectoryMetadata(File file) throws IOException {
        if (!file.isDirectory()) return;
        for (File listFile : file.listFiles())
            stripDirectoryMetadata(listFile);
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
     * Copies ResourcePackManager's own pack.mcmeta from resources to the output folder.
     * This ensures the merged resource pack has a valid, compatible pack.mcmeta.
     */
    private static void copyPluginPackMcmeta() {
        try {
            java.io.InputStream inputStream = ResourcePackManager.plugin.getResource("pack.mcmeta");
            if (inputStream == null) {
                Logger.warn("Could not find pack.mcmeta in plugin resources!");
                return;
            }
            File targetFile = new File(getOutputResourcePackFolder().getPath() + File.separatorChar + "pack.mcmeta");
            Files.copy(inputStream, targetFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            inputStream.close();
        } catch (IOException e) {
            Logger.warn("Failed to copy pack.mcmeta to merged resource pack!");
            e.printStackTrace();
        }
    }

    /**
     * Shader handling removed - reverting to 1.7.1 behavior where shaders were
     * copied/merged like any other files without special handling.
     */
    private static void removeIncompatibleShaders() {
        // No-op: 1.7.1 had no shader handling and worked fine
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

