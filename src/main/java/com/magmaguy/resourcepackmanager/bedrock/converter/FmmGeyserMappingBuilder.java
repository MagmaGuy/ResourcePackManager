package com.magmaguy.resourcepackmanager.bedrock.converter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.magmaguy.magmacore.util.Logger;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Generates Geyser v2 custom item mappings for FMM bone models.
 * All items are registered under minecraft:leather_horse_armor using the
 * definition type with model field matching FMM's setItemModel() value.
 */
public class FmmGeyserMappingBuilder {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /**
     * Data for a single bone's Geyser mapping.
     */
    public record BoneMapping(
            String itemModelKey,
            String bedrockIdentifier,
            String displayName,
            String iconKey
    ) {}

    /**
     * Generates the Geyser mappings JSON file.
     */
    public static void generate(List<BoneMapping> boneMappings, File outputFile) {
        List<Map<String, Object>> definitions = new ArrayList<>();

        for (BoneMapping mapping : boneMappings) {
            Map<String, Object> def = new LinkedHashMap<>();
            def.put("type", "definition");
            def.put("model", mapping.itemModelKey());
            def.put("bedrock_identifier", mapping.bedrockIdentifier());
            def.put("display_name", mapping.displayName());

            Map<String, Object> options = new LinkedHashMap<>();
            options.put("icon", mapping.iconKey());
            options.put("allow_offhand", true);
            def.put("bedrock_options", options);

            definitions.add(def);
        }

        Map<String, Object> items = new LinkedHashMap<>();
        items.put("minecraft:leather_horse_armor", definitions);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format_version", 2);
        root.put("items", items);

        try {
            if (outputFile.getParentFile() != null) {
                Files.createDirectories(outputFile.getParentFile().toPath());
            }
            try (FileWriter writer = new FileWriter(outputFile, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            Logger.info("[BedrockConverter] Generated Geyser mappings with " + boneMappings.size()
                    + " definitions: " + outputFile.getAbsolutePath());
        } catch (IOException e) {
            Logger.warn("[BedrockConverter] Failed to write Geyser mappings: " + e.getMessage());
        }
    }
}
