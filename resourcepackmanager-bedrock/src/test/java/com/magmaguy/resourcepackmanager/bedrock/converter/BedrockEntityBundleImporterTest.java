package com.magmaguy.resourcepackmanager.bedrock.converter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockEntityBundleImporterTest {
    @TempDir
    Path tempDir;

    @Test
    void copiesAllowedEntityBundleFilesOnly() throws Exception {
        Path merged = tempDir.resolve("merged");
        Path bedrock = tempDir.resolve("bedrock");
        Path bundle = merged.resolve("assets/freeminecraftmodels/rspm_bedrock_pack");
        Files.createDirectories(bundle.resolve("entity"));
        Files.createDirectories(bundle.resolve("models/entity/demo"));
        Files.createDirectories(bundle.resolve("textures/entity/demo"));
        Files.createDirectories(bundle.resolve("manifest"));

        Files.writeString(bundle.resolve("entity/demo.entity.json"), "{}");
        Files.writeString(bundle.resolve("models/entity/demo/model.geo.json"), "{}");
        Files.writeString(bundle.resolve("textures/entity/demo/model.png"), "png");
        Files.writeString(bundle.resolve("manifest/not_allowed.json"), "{}");

        int copied = BedrockEntityBundleImporter.importBundles(merged.toFile(), bedrock.toFile());

        assertEquals(3, copied);
        assertTrue(Files.exists(bedrock.resolve("entity/demo.entity.json")));
        assertTrue(Files.exists(bedrock.resolve("models/entity/demo/model.geo.json")));
        assertTrue(Files.exists(bedrock.resolve("textures/entity/demo/model.png")));
        assertFalse(Files.exists(bedrock.resolve("manifest/not_allowed.json")));
    }
}
