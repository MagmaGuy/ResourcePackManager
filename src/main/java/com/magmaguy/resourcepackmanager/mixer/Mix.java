package com.magmaguy.resourcepackmanager.mixer;

import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.ZipFile;
import com.magmaguy.resourcepackmanager.api.ResourcePackManagerAPI;
import com.magmaguy.resourcepackmanager.config.DefaultConfig;
import com.magmaguy.resourcepackmanager.thirdparty.*;
import com.magmaguy.resourcepackmanager.utils.SHA1Generator;
import lombok.Getter;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class Mix {
    private static final String resourcePackName = "ResourcePackManager_RSP";
    private static HashMap priorities;
    private static List<File> resourcePacks;
    private static List<String> orderedResourcePacks;
    @Getter
    private static File finalResourcePack;
    @Getter
    private static String finalSHA1;
    @Getter
    private static byte[] finalSHA1Bytes;
    private static File mixerFolder;

    private Mix() {
    }

    public static void initialize() {
        if (!initializeDefaultPluginFolders()) return;
        initializeThirdPartyResourcePacks();
        cloneToOutputAndUnzip();
        createOutputDefaultElements();
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
        priorities = new HashMap();
        List<ThirdPartyResourcePack> tempList = new ArrayList<>();
        List<ThirdPartyResourcePack> resourcePackManagers = Arrays.asList(
                new com.magmaguy.resourcepackmanager.thirdparty.ResourcePackManager(),
                new EliteMobs(),
                new FreeMinecraftModels(),
                new ModelEngine(),
                new ItemsAdder(),
                new Nova(),
                new Oraxen(),
                new BetterHUD(),
                new ValhallaMMO(),
                new VaneCore(),
                new BackpackPlus(),
                new RealisticSurvival()
        );

        //API resource packs
        resourcePackManagers.addAll(ResourcePackManagerAPI.thirdPartyResourcePackHashMap.values());

        resourcePackManagers.stream()
                .filter(ThirdPartyResourcePack::isEnabled)
                .forEach(tempList::add);

        resourcePacks = new ArrayList<>();
        List<File> customFiles = new ArrayList<>();
        List<File> thirdPartyFiles = new ArrayList<>();

        tempList.forEach(rsp -> {
            if (rsp.getMixerResourcePack() != null) {
                thirdPartyFiles.add(rsp.getMixerResourcePack());
            }
        });

        for (File file : mixerFolder.listFiles()) {
            boolean isThirdParty = false;
            for (File thirdPartyFile : thirdPartyFiles) {
                if (file.getName().equals(thirdPartyFile.getName())) {
                    isThirdParty = true;
                    break;
                }
            }
            if (!isThirdParty) {
                customFiles.add(file);
            }
        }

        for (int i = 0; i < DefaultConfig.getPriorityOrder().size(); i++) {
            boolean foundFileAtPriority = false;
            Iterator<ThirdPartyResourcePack> iterator = tempList.iterator();

            while (iterator.hasNext()) {
                ThirdPartyResourcePack thirdPartyResourcePack = iterator.next();
                if (thirdPartyResourcePack.getPriority() == i) {
                    File resourcePackFile = thirdPartyResourcePack.getMixerResourcePack();
                    if (resourcePackFile != null) {
                        resourcePacks.add(resourcePackFile);
                        orderedResourcePacks.add(resourcePackFile.getName().replace(".zip", ""));
                        iterator.remove();
                        foundFileAtPriority = true;
                        break;
                    }
                }
            }

            if (!foundFileAtPriority) {
                Iterator<File> fileIterator = customFiles.iterator();
                while (fileIterator.hasNext()) {
                    File file = fileIterator.next();
                    int priority = DefaultConfig.getPriorityOrder().indexOf(file.getName());
                    if (priority == i) {
                        resourcePacks.add(file);
                        orderedResourcePacks.add(file.getName().replace(".zip", ""));
                        fileIterator.remove();
                        Logger.info("Added custom resource pack " + file.getName() + " at priority " + i);
                    }
                }
            }
        }

        tempList.stream()
                .map(ThirdPartyResourcePack::getMixerResourcePack)
                .filter(Objects::nonNull)
                .forEach(resourcePack -> {
                    resourcePacks.add(resourcePack);
                    orderedResourcePacks.add(resourcePack.getName().replace(".zip", ""));
                    Logger.info("Added supported resource pack " + resourcePack.getName() + " without a priority!");
                });

        customFiles.forEach(customFile -> {
            resourcePacks.add(customFile);
            orderedResourcePacks.add(customFile.getName().replace(".zip", ""));
            Logger.info("Added custom resource pack " + customFile.getName() + " without a priority!");
        });
    }


    private static void cloneToOutputAndUnzip() {
        resourcePacks.forEach(resourcePack -> {
            try {
                if (resourcePack == null) {
                    Logger.warn("A resource pack was null by the time it was meant to be unzipped!");
                    return;
                }
                File file = new File(ResourcePackManager.plugin.getDataFolder().getAbsolutePath() + File.separatorChar + "output" + File.separatorChar + resourcePack.getName().replace(".zip", ""));
                ZipFile.unzip(resourcePack, file);
                stripDirectoryMetadata(file);
            } catch (Exception e) {
                if (resourcePack == null)
                    Logger.warn("Failed to extract resource pack! A file might be encrypted.");
                 else
                    Logger.warn("Failed to extract resource pack " + resourcePack.getName() + " ! The file might be encrypted.");
                e.printStackTrace();
            }
        });
    }

    private static void createOutputDefaultElements() {
        if (getOutputResourcePackFolder().exists()) {
            recursivelyDeleteDirectory(getOutputResourcePackFolder());
        }
        try {
            getOutputResourcePackFolder().mkdir();
        } catch (Exception e) {
            Logger.warn("Failed to create resource pack output directory");
            throw new RuntimeException(e);
        }

        List<File> orderedFiles = new ArrayList<>();
        for (String filename : orderedResourcePacks){
            orderedFiles.add(new File(getOutputFolder().getPath() + File.separatorChar + filename));
        }

        for (File file : orderedFiles) {
            if (file.getName().equals(resourcePackName + ".zip")) continue;
            if (!file.isDirectory()) {
                if (file.getName().endsWith(".zip")) continue;
                Logger.warn("Somehow a non-folder file made its way to the output folder! This isn't good. File: " + file.getAbsolutePath());
                continue;
            }
            for (File subFile : file.listFiles()) {
                recursivelyCopyDirectory(subFile, getOutputResourcePackFolder());
            }
        }
        if (!ZipFile.zip(getOutputResourcePackFolder(), getOutputResourcePackFolder().getPath() + ".zip")) {
            Logger.warn("Failed to zip merged resource pack!");
            return;
        }
        if (!DefaultConfig.getResourcePackRerouting().isEmpty() && !DefaultConfig.getResourcePackRerouting().isBlank()){
            try{
                File rerouteFolder = new File(ResourcePackManager.plugin.getDataFolder().getParentFile().getAbsolutePath() + File.separatorChar + DefaultConfig.getResourcePackRerouting());
                if (!rerouteFolder.exists()) {
                    Logger.warn("Failed to reroute zipped file to " + rerouteFolder.getAbsolutePath() + " because that folder does not exist!");
                } else if (!rerouteFolder.isDirectory()){
                    Logger.warn("Failed to reroute zipped file to " + rerouteFolder.getAbsolutePath() + " because that is a file and not a folder!");
                } else if (!ZipFile.zip(getOutputResourcePackFolder(), rerouteFolder.getPath() + ".zip")) {
                    Logger.warn("Failed to zip merged resource pack into reroute directory!");
                    return;
                }
            } catch (Exception e){
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
            target = new File(target.getAbsolutePath() + File.separatorChar + source.getName());
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

    private static void resolveFileCollision(File sourceFile, File targetFile) throws IOException {
        if (!targetFile.getName().endsWith(".json")) {
            //If the file isn't .json then it can't be merged, only replaced (such as with .png).
            //No further action is needed here since files are transferred over in priority order
            Logger.info("Hard collision for file " + targetFile.getPath() + " detected! Auto-resolved based on highest priority.");
            return;
        }

        FileReader sourceFileReader = new FileReader(sourceFile);
        FileReader targetFileReader = new FileReader(targetFile);

        JsonObject json1 = null;
        // Read JSON files
        try {
            json1 = JsonParser.parseReader(sourceFileReader).getAsJsonObject();
        } catch (Exception e) {
            Logger.warn("Malformed JSON for " + sourceFile.getAbsolutePath() + " !");
            try {
                JsonReader jsonReader = new JsonReader(sourceFileReader);
                jsonReader.setStrictness(Strictness.LENIENT);
                json1 = JsonParser.parseReader(jsonReader).getAsJsonObject();
                Logger.info(JsonParser.parseReader(jsonReader).getAsString());
            } catch (Exception ex) {
                Logger.warn("Your JSON " + sourceFile.getAbsolutePath() + " is so broken even lenient won't let me read it!");
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

        Logger.info("File " + targetFile.getName() + " successfully auto-merged!");
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
}
