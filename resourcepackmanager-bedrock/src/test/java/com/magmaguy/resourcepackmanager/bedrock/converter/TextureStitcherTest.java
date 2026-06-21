package com.magmaguy.resourcepackmanager.bedrock.converter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextureStitcherTest {
    @TempDir
    Path tempDir;

    @Test
    void genericSingleModelSkipsUnusedBoneIconCopies() throws Exception {
        Path merged = tempDir.resolve("merged");
        Path bedrock = tempDir.resolve("bedrock");
        writePng(merged.resolve("assets/demo/textures/item/full_size.png"));

        JsonObject model = JsonParser.parseString("""
                {
                  "textures": {
                    "layer0": "demo:item/full_size"
                  }
                }
                """).getAsJsonObject();

        TextureStitcher.StitchResult result = TextureStitcher.stitchSingleModel(
                "abc123", "abc123", model, merged.toFile(), bedrock.toFile());

        assertNotNull(result);
        assertTrue(Files.exists(bedrock.resolve("textures/entity/abc123/abc123/atlas.png")));
        assertFalse(Files.exists(bedrock.resolve("textures/items/abc123__abc123.png")));
        assertTrue(result.bonePrimaryIconPath().isEmpty());
    }

    @Test
    void legacyStitchStillWritesBoneIcons() throws Exception {
        Path merged = tempDir.resolve("merged");
        Path bedrock = tempDir.resolve("bedrock");
        writePng(merged.resolve("assets/demo/textures/item/full_size.png"));
        Path bone = tempDir.resolve("bone.json");
        Files.writeString(bone, """
                {
                  "textures": {
                    "0": "demo:item/full_size"
                  }
                }
                """);

        TextureStitcher.StitchResult result = TextureStitcher.stitch(
                "legacy_model", List.of(bone.toFile()), merged.toFile(), bedrock.toFile());

        assertNotNull(result);
        assertTrue(Files.exists(bedrock.resolve("textures/entity/legacy_model/atlas.png")));
        assertTrue(Files.exists(bedrock.resolve("textures/items/legacy_model__bone.png")));
        assertEquals("textures/items/legacy_model__bone", result.bonePrimaryIconPath().get("bone"));
    }

    private static void writePng(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        BufferedImage image = new BufferedImage(12, 12, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, 0xff336699);
            }
        }
        ImageIO.write(image, "PNG", path.toFile());
    }
}
