package com.magmaguy.resourcepackmanager.bedrock.converter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

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

    @Test
    void shortensLongEntityTexturePathsAndRewritesJsonReferences() throws Exception {
        Path merged = tempDir.resolve("merged");
        Path bedrock = tempDir.resolve("bedrock");
        Path bundle = merged.resolve("assets/freeminecraftmodels/rspm_bedrock_pack");
        String longTexture = "textures/entity/em_ag_ishardthefrostbitten/"
                + "skin_snow_winter_9a4f053acacee41e7e6296fb4891668c.png";
        String longTextureRef = longTexture.substring(0, longTexture.length() - ".png".length());

        Files.createDirectories(bundle.resolve("entity"));
        Files.createDirectories(bundle.resolve(longTexture).getParent());
        Files.writeString(bundle.resolve(longTexture), "png");
        Files.writeString(bundle.resolve("entity/demo.entity.json"), """
                {
                  "minecraft:client_entity": {
                    "description": {
                      "textures": {
                        "default": "%s",
                        "explicit_extension": "%s"
                      }
                    }
                  }
                }
                """.formatted(longTextureRef, longTexture));

        int copied = BedrockEntityBundleImporter.importBundles(merged.toFile(), bedrock.toFile());

        assertEquals(2, copied);
        assertFalse(Files.exists(bedrock.resolve(longTexture)));

        List<Path> textureFiles;
        try (Stream<Path> stream = Files.walk(bedrock.resolve("textures/entity"))) {
            textureFiles = stream.filter(Files::isRegularFile).toList();
        }
        assertEquals(1, textureFiles.size());

        String compactTexture = bedrock.relativize(textureFiles.get(0)).toString().replace('\\', '/');
        String compactTextureRef = compactTexture.substring(0, compactTexture.length() - ".png".length());
        assertTrue(compactTexture.length() < 80);
        assertTrue(compactTexture.startsWith("textures/entity/"));

        String rewrittenJson = Files.readString(bedrock.resolve("entity/demo.entity.json"));
        assertFalse(rewrittenJson.contains(longTextureRef));
        assertFalse(rewrittenJson.contains(longTexture));
        assertTrue(rewrittenJson.contains(compactTextureRef));
        assertTrue(rewrittenJson.contains(compactTexture));
    }

    @Test
    void shortensLongEntityBundleSupportFilesAndRewritesPathReferences() throws Exception {
        Path merged = tempDir.resolve("merged");
        Path bedrock = tempDir.resolve("bedrock");
        Path bundle = merged.resolve("assets/freeminecraftmodels/rspm_bedrock_pack");
        String longStem = "fmm_craftenmine_basic_item_pack_velocity_enhancer_mk2_crossbow_draw_start";
        String longGeometry = "models/entity/freeminecraftmodels/" + longStem + ".geo.json";
        String longRenderController = "render_controllers/" + longStem + ".render_controllers.json";
        String longAnimationController = "animation_controllers/" + longStem + ".animation_controllers.json";

        Files.createDirectories(bundle.resolve("entity"));
        Files.createDirectories(bundle.resolve(longGeometry).getParent());
        Files.createDirectories(bundle.resolve(longRenderController).getParent());
        Files.createDirectories(bundle.resolve(longAnimationController).getParent());
        Files.writeString(bundle.resolve(longGeometry), "{}");
        Files.writeString(bundle.resolve(longRenderController), "{}");
        Files.writeString(bundle.resolve(longAnimationController), "{}");
        Files.writeString(bundle.resolve("entity/demo.entity.json"), """
                {
                  "paths": [
                    "%s",
                    "%s",
                    "%s",
                    "%s",
                    "%s",
                    "%s"
                  ]
                }
                """.formatted(
                longGeometry, longRenderController, longAnimationController,
                "models/entity/freeminecraftmodels/" + longStem,
                "render_controllers/" + longStem,
                "animation_controllers/" + longStem));

        int copied = BedrockEntityBundleImporter.importBundles(merged.toFile(), bedrock.toFile());

        assertEquals(4, copied);
        assertFalse(Files.exists(bedrock.resolve(longGeometry)));
        assertFalse(Files.exists(bedrock.resolve(longRenderController)));
        assertFalse(Files.exists(bedrock.resolve(longAnimationController)));

        assertSingleCompactFile(bedrock.resolve("models/entity"));
        assertSingleCompactFile(bedrock.resolve("render_controllers"));
        assertSingleCompactFile(bedrock.resolve("animation_controllers"));

        String rewrittenJson = Files.readString(bedrock.resolve("entity/demo.entity.json"));
        assertFalse(rewrittenJson.contains(longStem));
        assertTrue(rewrittenJson.contains("models/entity/"));
        assertTrue(rewrittenJson.contains("render_controllers/"));
        assertTrue(rewrittenJson.contains("animation_controllers/"));
    }

    private static void assertSingleCompactFile(Path directory) throws Exception {
        List<Path> files;
        try (Stream<Path> stream = Files.walk(directory)) {
            files = stream.filter(Files::isRegularFile).toList();
        }
        assertEquals(1, files.size());
        String relative = directory.getParent().relativize(files.get(0)).toString().replace('\\', '/');
        assertTrue(relative.length() < 80, relative);
    }
}
