package com.magmaguy.resourcepackmanager.bedrock.model;

import lombok.Data;
import java.io.File;

/**
 * Represents a single FMM bone model discovered in the merged resource pack.
 */
@Data
public class FmmBoneModel {
    /** Model group name, e.g. "01_em_wolf" */
    private final String modelName;
    /** Bone name within the model, e.g. "bodyback" */
    private final String boneName;
    /** The Java model JSON file */
    private final File modelFile;
    /** item_model value FMM sets via setItemModel(), e.g. "freeminecraftmodels:01_em_wolf/bodyback" */
    private final String itemModelKey;
}
