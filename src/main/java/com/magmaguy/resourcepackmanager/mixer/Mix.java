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

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Stream;

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
        priorities = new HashMap<>();

        // Use a mutable list so we can addAll() to it
        List<ThirdPartyResourcePack> resourcePackManagers = new ArrayList<>(Arrays.asList(
                new com.magmaguy.resourcepackmanager.thirdparty.ResourcePackManager(),
                new EliteMobs(),
                new FreeMinecraftModels(),
                new ModelEngine(),
                new ItemsAdder(),
                new Nova(),
                new Oraxen(),
                new Nexo(),
                new BetterHUD(),
                new ValhallaMMO(),
                new VaneCore(),
                new BackpackPlus(),
                new RealisticSurvival()
        ));

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
                    Logger.info("Added supported resource pack " + packFile.getName() + " without a priority!");
                });

        // Any leftover custom files without explicit priority
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

//
//    @Getter
//    private static File finalBedrockResourcePack;
//    @Getter
//    private static String finalBedrockSHA1;
//
//    // Call this method in createOutputDefaultElements() after finalResourcePack is set
//    private static void generateBedrockResourcePack() {
//        if (finalResourcePack == null) {
//            Logger.warn("Cannot generate Bedrock resource pack - Java resource pack was not created successfully!");
//            return;
//        }
//
//        Logger.info("Starting Bedrock resource pack generation...");
//
//        File bedrockOutputFolder = new File(getOutputFolder().getAbsolutePath() + File.separatorChar + resourcePackName + "_Bedrock");
//
//        // Clean up any existing Bedrock folder
//        if (bedrockOutputFolder.exists()) {
//            recursivelyDeleteDirectory(bedrockOutputFolder);
//        }
//
//        try {
//            bedrockOutputFolder.mkdirs();
//
//            // Extract the Java resource pack to process it
//            File tempJavaFolder = new File(getOutputFolder().getAbsolutePath() + File.separatorChar + "temp_java_extract");
//            if (tempJavaFolder.exists()) {
//                recursivelyDeleteDirectory(tempJavaFolder);
//            }
//            tempJavaFolder.mkdirs();
//
//            ZipFile.unzip(finalResourcePack, tempJavaFolder);
//
//            // Generate Bedrock pack contents
//            generateBedrockManifest(bedrockOutputFolder);
//            generateBehaviorPackManifest(bedrockOutputFolder);
//            convertFreeMinecraftModelsAssets(tempJavaFolder, bedrockOutputFolder);
//
//            // Create the Bedrock resource pack zip
//            String bedrockPackName = resourcePackName + "_Bedrock.mcpack";
//            File bedrockZipFile = new File(getOutputFolder().getAbsolutePath() + File.separatorChar + bedrockPackName);
//
//            if (ZipFile.zip(bedrockOutputFolder, bedrockZipFile.getAbsolutePath())) {
//                finalBedrockResourcePack = bedrockZipFile;
//                try {
//                    finalBedrockSHA1 = SHA1Generator.sha1CodeString(finalBedrockResourcePack);
//                    Logger.info("Successfully generated Bedrock resource pack: " + bedrockPackName);
//                } catch (Exception e) {
//                    Logger.warn("Failed to generate SHA1 for Bedrock resource pack!");
//                }
//            } else {
//                Logger.warn("Failed to zip Bedrock resource pack!");
//            }
//
//            // Clean up temporary folders
//            recursivelyDeleteDirectory(tempJavaFolder);
//            recursivelyDeleteDirectory(bedrockOutputFolder);
//
//        } catch (Exception e) {
//            Logger.warn("Failed to generate Bedrock resource pack!");
//            e.printStackTrace();
//        }
//    }
//
//    private static void generateBedrockManifest(File bedrockOutputFolder) {
//        try {
//            JsonObject manifest = new JsonObject();
//            manifest.addProperty("format_version", 2);
//
//            // Header
//            JsonObject header = new JsonObject();
//            header.addProperty("name", "FreeMinecraftModels Bedrock");
//            header.addProperty("description", "Bedrock Edition conversion of FreeMinecraftModels resource pack");
//            header.addProperty("uuid", UUID.randomUUID().toString());
//            JsonArray headerVersion = new JsonArray();
//            headerVersion.add(1); headerVersion.add(0); headerVersion.add(0);
//            header.add("version", headerVersion);
//            JsonArray minEngineVersion = new JsonArray();
//            minEngineVersion.add(1); minEngineVersion.add(21); minEngineVersion.add(0);
//            header.add("min_engine_version", minEngineVersion);
//            manifest.add("header", header);
//
//            // Modules
//            JsonArray modules = new JsonArray();
//            JsonObject resourceModule = new JsonObject();
//            resourceModule.addProperty("type", "resources");
//            resourceModule.addProperty("uuid", UUID.randomUUID().toString());
//            JsonArray moduleVersion = new JsonArray();
//            moduleVersion.add(1); moduleVersion.add(0); moduleVersion.add(0);
//            resourceModule.add("version", moduleVersion);
//            modules.add(resourceModule);
//            manifest.add("modules", modules);
//
//            // Write manifest.json
//            File manifestFile = new File(bedrockOutputFolder, "manifest.json");
//            try (FileWriter writer = new FileWriter(manifestFile)) {
//                new Gson().toJson(manifest, writer);
//            }
//
//        } catch (Exception e) {
//            Logger.warn("Failed to generate Bedrock manifest!");
//            e.printStackTrace();
//        }
//    }
//
//    private static void generateBehaviorPackManifest(File bedrockOutputFolder) {
//        try {
//            JsonObject manifest = new JsonObject();
//            manifest.addProperty("format_version", 2);
//
//            // Header
//            JsonObject header = new JsonObject();
//            header.addProperty("name", "FreeMinecraftModels Bedrock Behavior");
//            header.addProperty("description", "Behavior pack for FreeMinecraftModels custom bone items");
//            header.addProperty("uuid", UUID.randomUUID().toString());
//            JsonArray headerVersion = new JsonArray();
//            headerVersion.add(1); headerVersion.add(0); headerVersion.add(0);
//            header.add("version", headerVersion);
//            JsonArray minEngineVersion = new JsonArray();
//            minEngineVersion.add(1); minEngineVersion.add(21); minEngineVersion.add(0);
//            header.add("min_engine_version", minEngineVersion);
//            manifest.add("header", header);
//
//            // Modules
//            JsonArray modules = new JsonArray();
//            JsonObject behaviorModule = new JsonObject();
//            behaviorModule.addProperty("type", "data");
//            behaviorModule.addProperty("uuid", UUID.randomUUID().toString());
//            JsonArray moduleVersion = new JsonArray();
//            moduleVersion.add(1); moduleVersion.add(0); moduleVersion.add(0);
//            behaviorModule.add("version", moduleVersion);
//            modules.add(behaviorModule);
//            manifest.add("modules", modules);
//
//            // Write BP manifest.json
//            File bpFolder = new File(bedrockOutputFolder, "BP");
//            bpFolder.mkdirs();
//            File manifestFile = new File(bpFolder, "manifest.json");
//            try (FileWriter writer = new FileWriter(manifestFile)) {
//                new Gson().toJson(manifest, writer);
//            }
//
//        } catch (Exception e) {
//            Logger.warn("Failed to generate Behavior Pack manifest!");
//            e.printStackTrace();
//        }
//    }
//
//    private static void convertFreeMinecraftModelsAssets(File javaFolder, File bedrockFolder) {
//        File freeMinecraftModelsAssets = new File(javaFolder, "assets" + File.separator + "freeminecraftmodels");
//        if (!freeMinecraftModelsAssets.exists()) {
//            Logger.info("No FreeMinecraftModels assets found to convert");
//            return;
//        }
//
//        Logger.info("Converting FreeMinecraftModels assets to Bedrock format...");
//
//        try {
//            // Create Bedrock directory structure
//            File attachablesDir = new File(bedrockFolder, "attachables");
//            File modelsDir = new File(bedrockFolder, "models" + File.separator + "entity");
//            File texturesDir = new File(bedrockFolder, "textures" + File.separator + "entity");
//
//            attachablesDir.mkdirs();
//            modelsDir.mkdirs();
//            texturesDir.mkdirs();
//
//            // Copy textures first
//            File javaTexturesDir = new File(freeMinecraftModelsAssets, "textures" + File.separator + "entity");
//            if (javaTexturesDir.exists()) {
//                copyTexturesRecursively(javaTexturesDir, texturesDir);
//            }
//
//            // Convert Java models to Bedrock - each bone becomes separate item
//            File javaModelsDir = new File(freeMinecraftModelsAssets, "models");
//            if (javaModelsDir.exists()) {
//                convertJavaModelsToBedrock(javaModelsDir, attachablesDir, modelsDir, bedrockFolder);
//            }
//
//        } catch (Exception e) {
//            Logger.warn("Failed to convert FreeMinecraftModels assets!");
//            e.printStackTrace();
//        }
//    }
//
//    private static void copyTexturesRecursively(File sourceDir, File targetDir) {
//        if (!sourceDir.exists() || !sourceDir.isDirectory()) return;
//
//        try {
//            for (File file : sourceDir.listFiles()) {
//                if (file.isDirectory()) {
//                    File newTargetDir = new File(targetDir, file.getName());
//                    newTargetDir.mkdirs();
//                    copyTexturesRecursively(file, newTargetDir);
//                } else if (file.getName().endsWith(".png")) {
//                    File targetFile = new File(targetDir, file.getName());
//                    Files.copy(file.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
//                }
//            }
//        } catch (Exception e) {
//            Logger.warn("Failed to copy textures from " + sourceDir.getName());
//            e.printStackTrace();
//        }
//    }
//
//    private static void convertJavaModelsToBedrock(File javaModelsDir, File attachablesDir, File modelsDir, File bedrockFolder) {
//        // Look for model folders (each model has its own folder with bone JSON files)
//        for (File modelFolder : javaModelsDir.listFiles()) {
//            if (!modelFolder.isDirectory()) continue;
//
//            String modelName = modelFolder.getName();
//
//            // Skip equipment folder - that's for 1.21.4+ format
//            if (modelName.equals("equipment")) continue;
//
//            Logger.info("Converting model: " + modelName);
//
//            try {
//                // Process each bone file individually - each becomes a separate item
//                convertModelBonesToBedrock(modelFolder, modelName, attachablesDir, modelsDir, bedrockFolder);
//
//            } catch (Exception e) {
//                Logger.warn("Failed to convert model: " + modelName);
//                e.printStackTrace();
//            }
//        }
//    }
//
//    private static void convertModelBonesToBedrock(File modelFolder, String modelName, File attachablesDir, File modelsDir, File bedrockFolder) {
//        // Process each bone file as a separate item/geometry
//        List<File> boneFiles = new ArrayList<>();
//        collectBoneFiles(modelFolder, boneFiles);
//
//        for (File boneFile : boneFiles) {
//            String boneName = boneFile.getName().replace(".json", "");
//
//            // Skip the root bone
//            if (boneName.equals("freeminecraftmodels_autogenerated_root")) continue;
//
//            Logger.info("Converting bone: " + boneName + " from model: " + modelName);
//
//            try {
//                // Create unique identifiers for this bone
//                String boneId = modelName + "_" + boneName;
//                String itemId = "fmm_bone_" + boneId;
//
//                // Convert bone to Bedrock geometry
//                convertSingleBoneToBedrock(boneFile, boneName, modelName, modelsDir);
//
//                // Create attachable for this bone
//                generateBoneAttachable(itemId, boneName, modelName, attachablesDir);
//
//                // Create behavior pack item for this bone
//                generateBoneItem(itemId, boneName, modelName, bedrockFolder);
//
//            } catch (Exception e) {
//                Logger.warn("Failed to convert bone: " + boneName + " from model: " + modelName);
//                e.printStackTrace();
//            }
//        }
//    }
//
//    private static void collectBoneFiles(File folder, List<File> boneFiles) {
//        for (File file : folder.listFiles()) {
//            if (file.isDirectory()) {
//                collectBoneFiles(file, boneFiles);
//            } else if (file.getName().endsWith(".json")) {
//                boneFiles.add(file);
//            }
//        }
//    }
//
//    private static void convertSingleBoneToBedrock(File boneFile, String boneName, String modelName, File modelsDir) {
//        try {
//            // Read Java bone model
//            JsonObject javaModel = JsonParser.parseReader(new FileReader(boneFile)).getAsJsonObject();
//
//            // Create Bedrock geometry
//            JsonObject geometry = new JsonObject();
//            geometry.addProperty("format_version", "1.21.0");
//
//            JsonArray geometryArray = new JsonArray();
//            JsonObject geometryDef = new JsonObject();
//
//            JsonObject description = new JsonObject();
//            description.addProperty("identifier", "geometry.fmm_bone." + modelName + "_" + boneName);
//            description.addProperty("texture_width", 64);
//            description.addProperty("texture_height", 64);
//            geometryDef.add("description", description);
//
//            JsonArray bones = new JsonArray();
//
//            // Create root bone
//            JsonObject rootBone = new JsonObject();
//            rootBone.addProperty("name", "root");
//            JsonArray pivot = new JsonArray();
//            pivot.add(0); pivot.add(0); pivot.add(0);
//            rootBone.add("pivot", pivot);
//
//            // Convert elements (cubes) to Bedrock format
//            if (javaModel.has("elements")) {
//                JsonArray elements = javaModel.getAsJsonArray("elements");
//                JsonArray cubes = new JsonArray();
//
//                for (int i = 0; i < elements.size(); i++) {
//                    JsonObject element = elements.get(i).getAsJsonObject();
//                    JsonObject bedrockCube = convertJavaCubeToBedrock(element);
//                    if (bedrockCube != null) {
//                        cubes.add(bedrockCube);
//                    }
//                }
//
//                if (cubes.size() > 0) {
//                    rootBone.add("cubes", cubes);
//                }
//            }
//
//            bones.add(rootBone);
//            geometryDef.add("bones", bones);
//            geometryArray.add(geometryDef);
//            geometry.add("minecraft:geometry", geometryArray);
//
//            // Write geometry file
//            File geoFile = new File(modelsDir, "fmm_bone_" + modelName + "_" + boneName + ".geo.json");
//            try (FileWriter writer = new FileWriter(geoFile)) {
//                new Gson().toJson(geometry, writer);
//            }
//
//        } catch (Exception e) {
//            Logger.warn("Failed to convert bone: " + boneName);
//            e.printStackTrace();
//        }
//    }
//
//    private static JsonObject convertJavaCubeToBedrock(JsonObject javaCube) {
//        try {
//            JsonObject bedrockCube = new JsonObject();
//
//            // Convert origin (from in Java)
//            if (javaCube.has("from")) {
//                JsonArray from = javaCube.getAsJsonArray("from");
//                JsonArray origin = new JsonArray();
//                origin.add(from.get(0));
//                origin.add(from.get(1));
//                origin.add(from.get(2));
//                bedrockCube.add("origin", origin);
//            }
//
//            // Convert size (to - from in Java)
//            if (javaCube.has("from") && javaCube.has("to")) {
//                JsonArray from = javaCube.getAsJsonArray("from");
//                JsonArray to = javaCube.getAsJsonArray("to");
//                JsonArray size = new JsonArray();
//                size.add(to.get(0).getAsDouble() - from.get(0).getAsDouble());
//                size.add(to.get(1).getAsDouble() - from.get(1).getAsDouble());
//                size.add(to.get(2).getAsDouble() - from.get(2).getAsDouble());
//                bedrockCube.add("size", size);
//            }
//
//            // Convert UV mapping - extract from faces
//            if (javaCube.has("faces")) {
//                JsonObject faces = javaCube.getAsJsonObject("faces");
//                // Use north face UV as default, or first available face
//                JsonObject northFace = null;
//
//                if (faces.has("north")) northFace = faces.getAsJsonObject("north");
//                else if (faces.has("south")) northFace = faces.getAsJsonObject("south");
//                else if (faces.has("east")) northFace = faces.getAsJsonObject("east");
//                else if (faces.has("west")) northFace = faces.getAsJsonObject("west");
//                else if (faces.has("up")) northFace = faces.getAsJsonObject("up");
//                else if (faces.has("down")) northFace = faces.getAsJsonObject("down");
//
//                if (northFace != null && northFace.has("uv")) {
//                    JsonArray javaUV = northFace.getAsJsonArray("uv");
//                    JsonArray bedrockUV = new JsonArray();
//                    bedrockUV.add(javaUV.get(0));
//                    bedrockUV.add(javaUV.get(1));
//                    bedrockCube.add("uv", bedrockUV);
//                } else {
//                    // Default UV
//                    JsonArray uv = new JsonArray();
//                    uv.add(0); uv.add(0);
//                    bedrockCube.add("uv", uv);
//                }
//            }
//
//            return bedrockCube;
//
//        } catch (Exception e) {
//            Logger.warn("Failed to convert Java cube to Bedrock format");
//            return null;
//        }
//    }
//
//    private static void generateBoneAttachable(String itemId, String boneName, String modelName, File attachablesDir) {
//        try {
//            JsonObject attachable = new JsonObject();
//            attachable.addProperty("format_version", "1.10.0");
//
//            JsonObject attachableData = new JsonObject();
//            JsonObject description = new JsonObject();
//            description.addProperty("identifier", "fmm:" + itemId);
//
//            JsonObject materials = new JsonObject();
//            materials.addProperty("default", "entity_alphatest");
//            materials.addProperty("enchanted", "entity_alphatest_glint");
//            description.add("materials", materials);
//
//            JsonObject textures = new JsonObject();
//            // Try to find appropriate texture path
//            String texturePath = "textures/entity/" + modelName + "/texture";
//            textures.addProperty("default", texturePath);
//            description.add("textures", textures);
//
//            JsonObject geometry = new JsonObject();
//            geometry.addProperty("default", "geometry.fmm_bone." + modelName + "_" + boneName);
//            description.add("geometry", geometry);
//
//            // Configure for handheld/inventory item display
//            JsonArray scripts = new JsonArray();
//            JsonObject animateScript = new JsonObject();
//            animateScript.addProperty("animate", "third_person_main_hand");
//            scripts.add(animateScript);
//            description.add("scripts", scripts);
//
//            attachableData.add("description", description);
//            attachable.add("minecraft:attachable", attachableData);
//
//            File attachableFile = new File(attachablesDir, itemId + ".json");
//            try (FileWriter writer = new FileWriter(attachableFile)) {
//                new Gson().toJson(attachable, writer);
//            }
//
//            Logger.info("Created attachable: fmm:" + itemId);
//
//        } catch (Exception e) {
//            Logger.warn("Failed to generate attachable for " + itemId);
//            e.printStackTrace();
//        }
//    }
//
//    private static void generateBoneItem(String itemId, String boneName, String modelName, File bedrockFolder) {
//        try {
//            // Create BP items folder
//            File bpItemsDir = new File(bedrockFolder, "BP" + File.separator + "items");
//            bpItemsDir.mkdirs();
//
//            JsonObject item = new JsonObject();
//            item.addProperty("format_version", "1.21.0");
//
//            JsonObject minecraftItem = new JsonObject();
//            JsonObject description = new JsonObject();
//            description.addProperty("identifier", "fmm:" + itemId);
//            description.addProperty("category", "Items");
//            minecraftItem.add("description", description);
//
//            JsonObject components = new JsonObject();
//
//            // Set max stack size
//            components.addProperty("minecraft:max_stack_size", 64);
//
//            // Set icon - use leather horse armor for now
//            components.addProperty("minecraft:icon", "leather_horse_armor");
//
//            // Set display name
//            JsonObject displayName = new JsonObject();
//            displayName.addProperty("value", modelName + " " + boneName.replace("_", " "));
//            components.add("minecraft:display_name", displayName);
//
//            // Set attachable for handheld items
//            JsonObject attachable = new JsonObject();
//            attachable.addProperty("slot", "mainhand");
//            attachable.addProperty("attachable", "fmm:" + itemId);
//            components.add("minecraft:attachable", attachable);
//
//            minecraftItem.add("components", components);
//            item.add("minecraft:item", minecraftItem);
//
//            File itemFile = new File(bpItemsDir, itemId + ".json");
//            try (FileWriter writer = new FileWriter(itemFile)) {
//                new Gson().toJson(item, writer);
//            }
//
//            Logger.info("Created custom bone item: fmm:" + itemId);
//
//        } catch (Exception e) {
//            Logger.warn("Failed to generate behavior pack item for " + itemId);
//            e.printStackTrace();
//        }
//    }

}

