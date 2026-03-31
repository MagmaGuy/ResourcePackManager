package com.magmaguy.resourcepackmanager.bedrock.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Generates and writes a Bedrock resource pack manifest.json.
 */
public class BedrockManifest {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /**
     * Writes a manifest.json to the given output directory.
     *
     * @param outputDir   the directory to write manifest.json into
     * @param pluginVersion the version string of the plugin (used in metadata)
     * @param contentHash a hash of the pack content used for deterministic UUID generation
     */
    public static void write(File outputDir, String pluginVersion, String contentHash) {
        if (outputDir == null) return;
        if (!outputDir.exists()) outputDir.mkdirs();

        String safeHash = contentHash != null ? contentHash : "default";
        UUID headerUuid = UUID.nameUUIDFromBytes(("rspm-bedrock-header-" + safeHash).getBytes(StandardCharsets.UTF_8));
        UUID moduleUuid = UUID.nameUUIDFromBytes(("rspm-bedrock-module-" + safeHash).getBytes(StandardCharsets.UTF_8));

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format_version", 2);

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("name", "ResourcePackManager Bedrock Pack");
        header.put("description", "Auto-generated Bedrock resource pack");
        header.put("uuid", headerUuid.toString());
        header.put("version", Arrays.asList(1, 0, 0));
        header.put("min_engine_version", Arrays.asList(1, 21, 0));
        manifest.put("header", header);

        Map<String, Object> module = new LinkedHashMap<>();
        module.put("type", "resources");
        module.put("uuid", moduleUuid.toString());
        module.put("version", Arrays.asList(1, 0, 0));
        manifest.put("modules", Collections.singletonList(module));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("authors", Collections.singletonList("ResourcePackManager"));
        String safeVersion = pluginVersion != null ? pluginVersion : "unknown";
        Map<String, Object> generatedWith = new LinkedHashMap<>();
        generatedWith.put("ResourcePackManager", Collections.singletonList(safeVersion));
        metadata.put("generated_with", generatedWith);
        manifest.put("metadata", metadata);

        File manifestFile = new File(outputDir, "manifest.json");
        try (FileWriter writer = new FileWriter(manifestFile, StandardCharsets.UTF_8)) {
            GSON.toJson(manifest, writer);
        } catch (IOException e) {
            com.magmaguy.magmacore.util.Logger.warn("Failed to write Bedrock manifest.json: " + e.getMessage());
        }
    }
}
