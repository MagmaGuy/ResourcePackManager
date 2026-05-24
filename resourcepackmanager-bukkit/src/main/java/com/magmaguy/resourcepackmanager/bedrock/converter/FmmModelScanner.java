package com.magmaguy.resourcepackmanager.bedrock.converter;

import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.bedrock.model.FmmBoneModel;

import java.io.File;
import java.util.*;

/**
 * Scans the merged resource pack for FreeMinecraftModels bone model files.
 * Looks in assets/freeminecraftmodels/models/{modelName}/{boneName}.json
 * Skips the display/ subdirectory (those are hand-held display models, not bone models).
 */
public class FmmModelScanner {

    private static final String FMM_NAMESPACE = "freeminecraftmodels";

    /**
     * Scans the merged pack for FMM bone models.
     *
     * @param mergedPackRoot root directory of the unzipped merged Java resource pack
     * @return map of modelName -> list of bone models in that model
     */
    public static Map<String, List<FmmBoneModel>> scan(File mergedPackRoot) {
        Map<String, List<FmmBoneModel>> result = new LinkedHashMap<>();

        File modelsDir = new File(mergedPackRoot, "assets/" + FMM_NAMESPACE + "/models");
        if (!modelsDir.isDirectory()) {
            Logger.info("[BedrockConverter] No FMM models directory found at " + modelsDir.getPath());
            return result;
        }

        File[] modelDirs = modelsDir.listFiles(File::isDirectory);
        if (modelDirs == null) return result;

        for (File modelDir : modelDirs) {
            String modelName = modelDir.getName();
            if ("display".equals(modelName)) continue;

            File[] boneFiles = modelDir.listFiles((dir, name) -> name.endsWith(".json"));
            if (boneFiles == null || boneFiles.length == 0) continue;

            List<FmmBoneModel> bones = new ArrayList<>();
            for (File boneFile : boneFiles) {
                String boneName = boneFile.getName().replaceAll("\\.json$", "");
                String itemModelKey = FMM_NAMESPACE + ":" + modelName + "/" + boneName;
                bones.add(new FmmBoneModel(modelName, boneName, boneFile, itemModelKey));
            }

            if (!bones.isEmpty()) {
                bones.sort(Comparator.comparing(FmmBoneModel::getBoneName));
                result.put(modelName, bones);
            }
        }

        int totalBones = result.values().stream().mapToInt(List::size).sum();
        Logger.info("[BedrockConverter] Found " + result.size() + " FMM models with " + totalBones + " total bones.");
        return result;
    }
}
