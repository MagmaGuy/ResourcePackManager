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

    /**
     * ItemsAdder remaps every auto item texture to a short {@code ia:<n>} sprite via an
     * atlas {@code single} source shipped inside a pack overlay, so the model references
     * {@code ia:627} while the real PNG lives at a content-namespace path. The resolver
     * must rewrite the ref to the real resource, else the Bedrock stitcher looks for the
     * non-existent {@code assets/ia/textures/627.png} and the whole pack fails to convert.
     */
    @Test
    void itemsAdderAtlasSpriteRefIsRewrittenToRealTexture() throws Exception {
        // Atlas lives inside an overlay directory, exactly like ItemsAdder's generated pack.
        Path atlas = tempDir.resolve("ia_overlay_modern_atlas/assets/minecraft/atlases/items.json");
        Files.createDirectories(atlas.getParent());
        Files.writeString(atlas, """
                { "sources": [
                  { "type": "single", "resource": "inkless:vanillasets/cake_sword", "sprite": "ia:627" }
                ] }
                """);

        Path texture = tempDir.resolve("assets/inkless/textures/vanillasets/cake_sword.png");
        Files.createDirectories(texture.getParent());
        Files.writeString(texture, "fake-png-bytes");

        Path model = tempDir.resolve("assets/inkless/models/item/ia_auto/cake_sword.json");
        Files.createDirectories(model.getParent());
        Files.writeString(model, """
                {"parent":"minecraft:item/handheld","textures":{"layer0":"ia:627"}}
                """);

        AssetResolver resolver = new AssetResolver(tempDir.toFile());

        ResolvedModel resolved = resolver.resolveModel("inkless:item/ia_auto/cake_sword").orElseThrow();
        assertEquals("inkless:vanillasets/cake_sword",
                resolved.mergedJson().getAsJsonObject("textures").get("layer0").getAsString(),
                "ia:627 sprite ref should be rewritten to the real texture resource");
        assertTrue(resolved.isHandheldVariant());
    }

    /** A pack with no atlas sprite remaps must leave normal texture refs untouched. */
    @Test
    void plainTextureRefIsUnchangedWithoutAtlas() throws Exception {
        Path model = tempDir.resolve("assets/inkless/models/item/ia_auto/cake_sword.json");
        Files.createDirectories(model.getParent());
        Files.writeString(model, """
                {"parent":"minecraft:item/handheld","textures":{"layer0":"inkless:item/cake_sword"}}
                """);

        AssetResolver resolver = new AssetResolver(tempDir.toFile());

        ResolvedModel resolved = resolver.resolveModel("inkless:item/ia_auto/cake_sword").orElseThrow();
        assertEquals("inkless:item/cake_sword",
                resolved.mergedJson().getAsJsonObject("textures").get("layer0").getAsString());
    }
}
