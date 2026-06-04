package com.magmaguy.resourcepackmanager.bedrock.generic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void missingLeafModelIsUnresolvedInsteadOfEmptyGenericModel() {
        AssetResolver resolver = new AssetResolver(tempDir.toFile());

        assertTrue(resolver.resolveModel("elitemobs:ui/redcross").isEmpty());
    }

    @Test
    void flatModelWithMissingVanillaParentStillResolves() throws Exception {
        Path model = tempDir.resolve("assets/elitemobs/models/ui/redcross.json");
        Files.createDirectories(model.getParent());
        Files.writeString(model, """
                {
                  "parent": "item/generated",
                  "textures": {
                    "layer0": "elitemobs:ui/redcross"
                  }
                }
                """);

        AssetResolver resolver = new AssetResolver(tempDir.toFile());

        ResolvedModel resolved = resolver.resolveModel("elitemobs:ui/redcross").orElseThrow();
        assertTrue(resolved.isFlatBuiltin());
        assertEquals("minecraft:item/generated", resolved.rootParent());
        assertEquals("elitemobs:ui/redcross",
                resolved.mergedJson().getAsJsonObject("textures").get("layer0").getAsString());
    }
}
