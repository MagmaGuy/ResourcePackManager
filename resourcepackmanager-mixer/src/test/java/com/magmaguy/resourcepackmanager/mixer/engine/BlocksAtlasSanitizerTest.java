package com.magmaguy.resourcepackmanager.mixer.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlocksAtlasSanitizerTest {

    @Test
    void everyDirectoryUsesOnlyPackTexturesAndUnsafeSpritesUseSafeAliases(@TempDir Path tempDir) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("pack.mcmeta", utf8("""
                {"pack":{"pack_format":75,"description":"all-directory atlas test"}}
                """));
        entries.put("assets/minecraft/atlases/blocks.json", utf8("""
                {"sources":[
                  {"type":"directory","source":"gui","prefix":"gui/"},
                  {"type":"filter","pattern":{"namespace":"minecraft","path":"gui/ignored"}},
                  {"type":"directory","source":"ui","prefix":"ui/"},
                  {"type":"directory","source":"entity","prefix":"entity/"},
                  {"type":"minecraft:single","resource":"minecraft:entity/bell/bell_body"},
                  {"type":"single","resource":"elitemobs:ui/icon","sprite":"custom:aliased_icon"}
                ]}
                """));
        entries.put("assets/custom/atlases/blocks.json", utf8("""
                {"sources":[{"type":"directory","source":"gui","prefix":"custom/"}]}
                """));
        entries.put("assets/elitemobs/textures/gui/menu.png", png(32, 32));
        entries.put("assets/elitemobs/textures/gui/Uppercase.png", png(16, 16));
        entries.put("assets/elitemobs/textures/ui/icon.png", png(16, 16));
        entries.put("assets/elitemobs/textures/ui/space_nosplit.png", png(1, 1));
        entries.put("assets/freeminecraftmodels/textures/entity/model.png", png(16, 16));
        entries.put("assets/BadNamespace/textures/gui/ignored.png", png(16, 16));

        MixOutput output = runMix(tempDir, createPack(tempDir.resolve("all-directories.zip"), entries));

        JsonArray actualSources = readJson(output.mergedDir().toPath()
                .resolve("assets/minecraft/atlases/blocks.json")).getAsJsonArray("sources");
        JsonArray expectedSources = JsonParser.parseString("""
                [
                  {"type":"minecraft:single","resource":"elitemobs:gui/menu"},
                  {"type":"filter","pattern":{"namespace":"minecraft","path":"gui/ignored"}},
                  {"type":"minecraft:single","resource":"elitemobs:ui/icon"},
                  {
                    "type":"minecraft:single",
                    "resource":"resourcepackmanager_internal:mip_safe/elitemobs/ui/space_nosplit",
                    "sprite":"elitemobs:ui/space_nosplit"
                  },
                  {"type":"minecraft:single","resource":"freeminecraftmodels:entity/model"},
                  {"type":"minecraft:single","resource":"minecraft:entity/bell/bell_body"},
                  {"type":"single","resource":"elitemobs:ui/icon","sprite":"custom:aliased_icon"}
                ]
                """).getAsJsonArray();
        assertEquals(expectedSources, actualSources);
        assertFalse(actualSources.toString().contains("minecraft:gui/sprites/notification/1"),
                "Directory materialization must not enumerate client-only vanilla textures");

        Path original = output.mergedDir().toPath()
                .resolve("assets/elitemobs/textures/ui/space_nosplit.png");
        Path safeCopy = output.mergedDir().toPath()
                .resolve("assets/resourcepackmanager_internal/textures/mip_safe/elitemobs/ui/space_nosplit.png");
        assertImageSize(original, 1, 1);
        assertImageSize(safeCopy, 16, 16);

        JsonArray nonCanonicalAtlas = readJson(output.mergedDir().toPath()
                .resolve("assets/custom/atlases/blocks.json")).getAsJsonArray("sources");
        assertEquals(JsonParser.parseString("""
                [{"type":"directory","source":"gui","prefix":"custom/"}]
                """).getAsJsonArray(), nonCanonicalAtlas,
                "Only the canonical minecraft blocks atlas participates in block stitching");
    }

    @Test
    void directoryExpansionsPreserveSourceOrderPrefixesAndAliases(@TempDir Path tempDir) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("pack.mcmeta", utf8("""
                {"pack":{"pack_format":75,"description":"atlas materialization test"}}
                """));
        entries.put("assets/minecraft/atlases/blocks.json", utf8("""
                {"sources":[
                  {"type":"minecraft:directory","source":"entity/conduit","prefix":"entity/conduit/"},
                  {"type":"directory","source":"entity","prefix":"entity/"},
                  {"type":"minecraft:single","resource":"minecraft:entity/bell/bell_body"},
                  {"type":"single","resource":"entity/pack_owned"},
                  {"type":"single","resource":"custom:entity/aliased","sprite":"custom:entity/renamed"},
                  {"type":"minecraft:directory","source":"entity","prefix":"entity/"},
                  {"type":"filter","pattern":{"namespace":"minecraft","path":"entity/ignored"}},
                  {"type":"directory","source":"entity","prefix":"custom_entity/"}
                ]}
                """));
        entries.put("assets/minecraft/atlases/gui.json", utf8("""
                {"sources":[{"type":"directory","source":"entity","prefix":"entity/"}]}
                """));
        entries.put("assets/freeminecraftmodels/textures/entity/zeta.png", png(16, 16));
        entries.put("assets/elitemobs/textures/entity/equipment/humanoid/bronze.png", png(16, 16));
        entries.put("assets/custom/textures/entity/nested/alpha.png", png(16, 16));
        entries.put("assets/custom/textures/entity/aliased.png", png(16, 16));
        entries.put("assets/minecraft/textures/entity/pack_owned.png", png(16, 16));
        entries.put("assets/custom/textures/entity/nested/alpha.png.mcmeta", utf8("""
                {"animation":{"frametime":2}}
                """));
        entries.put("assets/custom/textures/entity/not-a-texture.txt", new byte[]{5});
        entries.put("assets/custom/textures/item/entity.png", new byte[]{6});

        MixOutput output = runMix(tempDir, createPack(tempDir.resolve("input.zip"), entries));

        JsonArray actualSources = readJson(output.mergedDir().toPath()
                .resolve("assets/minecraft/atlases/blocks.json")).getAsJsonArray("sources");
        JsonArray expectedSources = JsonParser.parseString("""
                [
                  {"type":"minecraft:single","resource":"custom:entity/aliased"},
                  {"type":"minecraft:single","resource":"custom:entity/nested/alpha"},
                  {"type":"minecraft:single","resource":"elitemobs:entity/equipment/humanoid/bronze"},
                  {"type":"minecraft:single","resource":"freeminecraftmodels:entity/zeta"},
                  {"type":"minecraft:single","resource":"minecraft:entity/pack_owned"},
                  {"type":"minecraft:single","resource":"minecraft:entity/bell/bell_body"},
                  {"type":"single","resource":"entity/pack_owned"},
                  {"type":"single","resource":"custom:entity/aliased","sprite":"custom:entity/renamed"},
                  {"type":"minecraft:single","resource":"custom:entity/aliased"},
                  {"type":"minecraft:single","resource":"custom:entity/nested/alpha"},
                  {"type":"minecraft:single","resource":"elitemobs:entity/equipment/humanoid/bronze"},
                  {"type":"minecraft:single","resource":"freeminecraftmodels:entity/zeta"},
                  {"type":"minecraft:single","resource":"minecraft:entity/pack_owned"},
                  {"type":"filter","pattern":{"namespace":"minecraft","path":"entity/ignored"}},
                  {"type":"minecraft:single","resource":"custom:entity/aliased","sprite":"custom:custom_entity/aliased"},
                  {"type":"minecraft:single","resource":"custom:entity/nested/alpha","sprite":"custom:custom_entity/nested/alpha"},
                  {"type":"minecraft:single","resource":"elitemobs:entity/equipment/humanoid/bronze","sprite":"elitemobs:custom_entity/equipment/humanoid/bronze"},
                  {"type":"minecraft:single","resource":"freeminecraftmodels:entity/zeta","sprite":"freeminecraftmodels:custom_entity/zeta"},
                  {"type":"minecraft:single","resource":"minecraft:entity/pack_owned","sprite":"minecraft:custom_entity/pack_owned"}
                ]
                """).getAsJsonArray();
        assertEquals(expectedSources, actualSources);
        assertFalse(actualSources.toString().contains("minecraft:entity/fishing/fishing_hook"),
                "RSPM must not enumerate textures supplied only by the Minecraft client");

        JsonArray guiSources = readJson(output.mergedDir().toPath()
                .resolve("assets/minecraft/atlases/gui.json")).getAsJsonArray("sources");
        assertEquals(JsonParser.parseString("""
                [{"type":"directory","source":"entity","prefix":"entity/"}]
                """).getAsJsonArray(), guiSources, "Only the blocks atlas is normalized");
    }

    @Test
    void everyExistingOverlayAtlasUsesTheAllDeclaredLayersTextureUnion(@TempDir Path tempDir) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("pack.mcmeta", utf8("""
                {
                  "pack":{"min_format":65,"max_format":75,"description":"overlay atlas test"},
                  "overlays":{"entries":[
                    {"directory":"current","min_format":65,"max_format":72},
                    {"directory":"future","min_format":70,"max_format":75}
                  ]}
                }
                """));
        entries.put("assets/minecraft/atlases/blocks.json", utf8("""
                {"sources":[{"type":"directory","source":"entity","prefix":"entity/"}]}
                """));
        entries.put("assets/basepack/textures/entity/base.png", png(16, 16));
        entries.put("current/assets/minecraft/atlases/blocks.json", utf8("""
                {"sources":[
                  {"type":"filter","pattern":{"namespace":"currentpack","path":"entity/current"}},
                  {"type":"minecraft:directory","source":"entity","prefix":"entity/"},
                  {"type":"minecraft:single","resource":"minecraft:entity/enchantment/enchanting_table_book"}
                ]}
                """));
        entries.put("current/assets/currentpack/textures/entity/current.png", png(16, 16));
        entries.put("future/assets/futurepack/textures/entity/future.png", png(16, 16));

        MixOutput output = runMix(tempDir, createPack(tempDir.resolve("overlays.zip"), entries));

        JsonArray baseSources = readJson(output.mergedDir().toPath()
                .resolve("assets/minecraft/atlases/blocks.json")).getAsJsonArray("sources");
        assertEquals(JsonParser.parseString("""
                [
                  {"type":"minecraft:single","resource":"basepack:entity/base"},
                  {"type":"minecraft:single","resource":"currentpack:entity/current"},
                  {"type":"minecraft:single","resource":"futurepack:entity/future"}
                ]
                """).getAsJsonArray(), baseSources,
                "The shared base atlas must name overlay-only pack textures even while overlays are inactive");

        JsonArray currentSources = readJson(output.mergedDir().toPath()
                .resolve("current/assets/minecraft/atlases/blocks.json")).getAsJsonArray("sources");
        assertEquals(JsonParser.parseString("""
                [
                  {"type":"minecraft:single","resource":"basepack:entity/base"},
                  {"type":"minecraft:single","resource":"currentpack:entity/current"},
                  {"type":"minecraft:single","resource":"futurepack:entity/future"},
                  {"type":"filter","pattern":{"namespace":"currentpack","path":"entity/current"}},
                  {"type":"minecraft:single","resource":"basepack:entity/base"},
                  {"type":"minecraft:single","resource":"currentpack:entity/current"},
                  {"type":"minecraft:single","resource":"futurepack:entity/future"},
                  {"type":"minecraft:single","resource":"minecraft:entity/enchantment/enchanting_table_book"}
                ]
                """).getAsJsonArray(), currentSources,
                "Overlapping active layers need every physical match; the filter boundary also prevents global dedupe");
        assertFalse(Files.exists(output.mergedDir().toPath()
                        .resolve("future/assets/minecraft/atlases/blocks.json")),
                "Materialization must not synthesize atlas files for overlays that do not define one");
    }

    @Test
    void unsafeVariantInAnyLayerUsesOneCanonicalAliasAndCopiesEveryLayer(@TempDir Path tempDir) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("pack.mcmeta", utf8("""
                {
                  "pack":{"min_format":65,"max_format":75,"description":"layered mip test"},
                  "overlays":{"entries":[
                    {"directory":"layer","min_format":65,"max_format":75}
                  ]}
                }
                """));
        entries.put("assets/minecraft/atlases/blocks.json", utf8("""
                {"sources":[
                  {"type":"directory","source":"ui","prefix":"ui/"},
                  {"type":"single","resource":"shared:ui/layered","sprite":"custom:layered"}
                ]}
                """));
        entries.put("assets/shared/textures/ui/layered.png", png(16, 16));
        entries.put("layer/assets/minecraft/atlases/blocks.json", utf8("""
                {"sources":[
                  {"type":"filter","pattern":{"namespace":"custom","path":"never"}}
                ]}
                """));
        entries.put("layer/assets/shared/textures/ui/layered.png", png(24, 24));

        MixOutput output = runMix(tempDir, createPack(tempDir.resolve("layered.zip"), entries));
        Path merged = output.mergedDir().toPath();
        String internalResource = "resourcepackmanager_internal:mip_safe/shared/ui/layered";

        JsonArray baseSources = readJson(merged.resolve("assets/minecraft/atlases/blocks.json"))
                .getAsJsonArray("sources");
        assertEquals(JsonParser.parseString("""
                [
                  {"type":"minecraft:single","resource":"resourcepackmanager_internal:mip_safe/shared/ui/layered","sprite":"shared:ui/layered"},
                  {"type":"single","resource":"resourcepackmanager_internal:mip_safe/shared/ui/layered","sprite":"custom:layered"}
                ]
                """).getAsJsonArray(), baseSources);
        assertTrue(baseSources.toString().contains(internalResource));

        JsonArray overlaySources = readJson(merged.resolve("layer/assets/minecraft/atlases/blocks.json"))
                .getAsJsonArray("sources");
        assertEquals(JsonParser.parseString("""
                [
                  {"type":"minecraft:single","resource":"resourcepackmanager_internal:mip_safe/shared/ui/layered","sprite":"shared:ui/layered"},
                  {"type":"single","resource":"resourcepackmanager_internal:mip_safe/shared/ui/layered","sprite":"custom:layered"},
                  {"type":"filter","pattern":{"namespace":"custom","path":"never"}}
                ]
                """).getAsJsonArray(), overlaySources,
                "Existing single aliases and generated singles must share the same internal resource");

        Path baseOriginal = merged.resolve("assets/shared/textures/ui/layered.png");
        Path baseSafe = merged.resolve(
                "assets/resourcepackmanager_internal/textures/mip_safe/shared/ui/layered.png");
        Path overlayOriginal = merged.resolve("layer/assets/shared/textures/ui/layered.png");
        Path overlaySafe = merged.resolve(
                "layer/assets/resourcepackmanager_internal/textures/mip_safe/shared/ui/layered.png");
        assertArrayEquals(Files.readAllBytes(baseOriginal), Files.readAllBytes(baseSafe),
                "A safe physical layer must still provide a byte-equivalent internal override");
        assertImageSize(overlayOriginal, 24, 24);
        assertImageSize(overlaySafe, 32, 32);
        assertNearestNeighbour(overlayOriginal, overlaySafe);
    }

    @Test
    void animationFrameRulesDriveIndependentPerAxisSafeCopies(@TempDir Path tempDir) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("pack.mcmeta", utf8("""
                {"pack":{"pack_format":75,"description":"animation mip test"}}
                """));
        entries.put("assets/minecraft/atlases/blocks.json", utf8("""
                {"sources":[{"type":"directory","source":"ui","prefix":"ui/"}]}
                """));
        entries.put("assets/custom/textures/ui/animated.png", png(8, 48));
        entries.put("assets/custom/textures/ui/animated.png.mcmeta", utf8("""
                {"animation":{"frametime":3,"frames":[0,1,2,3,4,5]}}
                """));
        entries.put("assets/custom/textures/ui/explicit.png", png(24, 48));
        entries.put("assets/custom/textures/ui/explicit.png.mcmeta", utf8("""
                {"animation":{"width":24,"height":24,"frametime":2}}
                """));
        entries.put("assets/custom/textures/ui/height_only.png", png(48, 8));
        entries.put("assets/custom/textures/ui/height_only.png.mcmeta", utf8("""
                {"animation":{"height":8,"interpolate":true}}
                """));
        entries.put("assets/custom/textures/ui/safe.png", png(32, 48));
        entries.put("assets/custom/textures/ui/static_24.png", png(24, 24));
        entries.put("assets/custom/textures/ui/width_only.png", png(8, 48));
        entries.put("assets/custom/textures/ui/width_only.png.mcmeta", utf8("""
                {"animation":{"width":8,"frametime":4}}
                """));

        MixOutput output = runMix(tempDir, createPack(tempDir.resolve("animations.zip"), entries));
        Path merged = output.mergedDir().toPath();
        JsonArray sources = readJson(merged.resolve("assets/minecraft/atlases/blocks.json"))
                .getAsJsonArray("sources");
        assertEquals(JsonParser.parseString("""
                [
                  {"type":"minecraft:single","resource":"resourcepackmanager_internal:mip_safe/custom/ui/animated","sprite":"custom:ui/animated"},
                  {"type":"minecraft:single","resource":"resourcepackmanager_internal:mip_safe/custom/ui/explicit","sprite":"custom:ui/explicit"},
                  {"type":"minecraft:single","resource":"resourcepackmanager_internal:mip_safe/custom/ui/height_only","sprite":"custom:ui/height_only"},
                  {"type":"minecraft:single","resource":"custom:ui/safe"},
                  {"type":"minecraft:single","resource":"resourcepackmanager_internal:mip_safe/custom/ui/static_24","sprite":"custom:ui/static_24"},
                  {"type":"minecraft:single","resource":"resourcepackmanager_internal:mip_safe/custom/ui/width_only","sprite":"custom:ui/width_only"}
                ]
                """).getAsJsonArray(), sources);

        Path internal = merged.resolve("assets/resourcepackmanager_internal/textures/mip_safe/custom/ui");
        assertImageSize(internal.resolve("animated.png"), 16, 96);
        assertImageSize(internal.resolve("explicit.png"), 32, 64);
        assertImageSize(internal.resolve("height_only.png"), 48, 16);
        assertImageSize(internal.resolve("static_24.png"), 32, 32);
        assertImageSize(internal.resolve("width_only.png"), 16, 48);
        assertFalse(Files.exists(internal.resolve("safe.png")),
                "A frame already divisible by sixteen must not be copied");

        assertEquals(readJson(merged.resolve("assets/custom/textures/ui/animated.png.mcmeta")),
                readJson(internal.resolve("animated.png.mcmeta")),
                "Implicit square animation frames scale with the sheet and need no metadata rewrite");
        assertEquals(JsonParser.parseString("""
                {"animation":{"width":32,"height":32,"frametime":2}}
                """).getAsJsonObject(), readJson(internal.resolve("explicit.png.mcmeta")));
        assertEquals(JsonParser.parseString("""
                {"animation":{"height":16,"interpolate":true}}
                """).getAsJsonObject(), readJson(internal.resolve("height_only.png.mcmeta")));
        assertEquals(JsonParser.parseString("""
                {"animation":{"width":16,"frametime":4}}
                """).getAsJsonObject(), readJson(internal.resolve("width_only.png.mcmeta")));
        assertEquals(24, readJson(merged.resolve("assets/custom/textures/ui/explicit.png.mcmeta"))
                .getAsJsonObject("animation").get("width").getAsInt(),
                "The source metadata must remain untouched for non-atlas resource loaders");
        assertNearestNeighbour(merged.resolve("assets/custom/textures/ui/static_24.png"),
                internal.resolve("static_24.png"));
    }

    @Test
    void nonIntegralAnimationSheetScaleFailsWithItsExactPath(@TempDir Path tempDir) throws Exception {
        Map<String, byte[]> entries = minimalUiDirectoryPack();
        entries.put("assets/custom/textures/ui/non_integral.png", png(25, 48));
        entries.put("assets/custom/textures/ui/non_integral.png.mcmeta", utf8("""
                {"animation":{"width":24,"height":24}}
                """));
        Path pack = createPack(tempDir.resolve("non-integral-sheet.zip"), entries);

        IOException error = assertThrows(IOException.class, () -> runMix(tempDir, pack));
        assertTrue(normalizedMessage(error).contains("assets/custom/textures/ui/non_integral.png"),
                error::getMessage);
    }

    @Test
    void reducibleRationalSheetScaleIsRepresentedExactly(@TempDir Path tempDir) throws Exception {
        Map<String, byte[]> entries = minimalUiDirectoryPack();
        entries.put("assets/custom/textures/ui/reducible.png", png(18, 16));
        entries.put("assets/custom/textures/ui/reducible.png.mcmeta", utf8("""
                {"animation":{"width":12,"height":16}}
                """));

        MixOutput output = runMix(tempDir, createPack(tempDir.resolve("reducible-sheet.zip"), entries));
        Path merged = output.mergedDir().toPath();
        Path original = merged.resolve("assets/custom/textures/ui/reducible.png");
        Path safe = merged.resolve(
                "assets/resourcepackmanager_internal/textures/mip_safe/custom/ui/reducible.png");
        assertImageSize(safe, 24, 16);
        assertNearestNeighbour(original, safe);
        assertEquals(JsonParser.parseString("""
                {"animation":{"width":16,"height":16}}
                """).getAsJsonObject(), readJson(Path.of(safe + ".mcmeta")));
    }

    @Test
    void unchangedSafeFrameAllowsARepresentableFractionalSheetGrid(@TempDir Path tempDir) throws Exception {
        Map<String, byte[]> entries = minimalUiDirectoryPack();
        entries.put("assets/custom/textures/ui/unchanged.png", png(17, 16));
        entries.put("assets/custom/textures/ui/unchanged.png.mcmeta", utf8("""
                {"animation":{"width":16,"height":16}}
                """));

        MixOutput output = runMix(tempDir, createPack(tempDir.resolve("unchanged-sheet.zip"), entries));
        Path merged = output.mergedDir().toPath();
        JsonArray sources = readJson(merged.resolve("assets/minecraft/atlases/blocks.json"))
                .getAsJsonArray("sources");
        assertEquals(JsonParser.parseString("""
                [{"type":"minecraft:single","resource":"custom:ui/unchanged"}]
                """).getAsJsonArray(), sources);
        assertFalse(Files.exists(merged.resolve(
                "assets/resourcepackmanager_internal/textures/mip_safe/custom/ui/unchanged.png")));
    }

    @Test
    void oddStaticAxesRoundToTheNextIndependentMipMultiple(@TempDir Path tempDir) throws Exception {
        Map<String, byte[]> entries = minimalUiDirectoryPack();
        entries.put("assets/custom/textures/ui/dialoguebox.png", png(427, 256));
        entries.put("assets/custom/textures/ui/dialoguebox_r.png", png(213, 256));
        entries.put("assets/custom/textures/ui/boss_bar.png", png(182, 5));

        MixOutput output = runMix(tempDir, createPack(tempDir.resolve("odd-static-axes.zip"), entries));
        Path merged = output.mergedDir().toPath();
        Path internal = merged.resolve("assets/resourcepackmanager_internal/textures/mip_safe/custom/ui");
        assertImageSize(internal.resolve("dialoguebox.png"), 432, 256);
        assertImageSize(internal.resolve("dialoguebox_r.png"), 224, 256);
        assertImageSize(internal.resolve("boss_bar.png"), 192, 16);
        assertNearestNeighbour(merged.resolve("assets/custom/textures/ui/dialoguebox.png"),
                internal.resolve("dialoguebox.png"));
        assertNearestNeighbour(merged.resolve("assets/custom/textures/ui/dialoguebox_r.png"),
                internal.resolve("dialoguebox_r.png"));
        assertNearestNeighbour(merged.resolve("assets/custom/textures/ui/boss_bar.png"),
                internal.resolve("boss_bar.png"));
    }

    @Test
    void overflowingLogicalFrameRoundFailsWithItsExactPath(@TempDir Path tempDir) throws Exception {
        Map<String, byte[]> entries = minimalUiDirectoryPack();
        entries.put("assets/custom/textures/ui/overflow.png", png(1, 1));
        entries.put("assets/custom/textures/ui/overflow.png.mcmeta", utf8("""
                {"animation":{"width":2147483647,"height":1}}
                """));
        Path pack = createPack(tempDir.resolve("overflow-frame.zip"), entries);

        IOException error = assertThrows(IOException.class, () -> runMix(tempDir, pack));
        assertTrue(normalizedMessage(error).contains("assets/custom/textures/ui/overflow.png"),
                error::getMessage);
    }

    @Test
    void undecodableEmittedPngFailsWithItsExactPath(@TempDir Path tempDir) throws Exception {
        Map<String, byte[]> entries = minimalUiDirectoryPack();
        entries.put("assets/custom/textures/ui/broken.png", new byte[]{0x13, 0x37});
        Path pack = createPack(tempDir.resolve("broken-png.zip"), entries);

        IOException error = assertThrows(IOException.class, () -> runMix(tempDir, pack));
        assertTrue(normalizedMessage(error).contains("assets/custom/textures/ui/broken.png"), error::getMessage);
    }

    @Test
    void malformedEmittedAnimationMetadataFailsWithItsExactPath(@TempDir Path tempDir) throws Exception {
        Map<String, byte[]> entries = minimalUiDirectoryPack();
        entries.put("assets/custom/textures/ui/broken.png", png(1, 1));
        entries.put("assets/custom/textures/ui/broken.png.mcmeta", utf8("{not-json"));
        Path pack = createPack(tempDir.resolve("broken-metadata.zip"), entries);

        IOException error = assertThrows(IOException.class, () -> runMix(tempDir, pack));
        assertTrue(normalizedMessage(error).contains("assets/custom/textures/ui/broken.png.mcmeta"),
                error::getMessage);
    }

    @Test
    void malformedCanonicalBlocksAtlasFailsWithItsExactPath(@TempDir Path tempDir) throws Exception {
        Map<String, byte[]> entries = minimalUiDirectoryPack();
        entries.put("assets/minecraft/atlases/blocks.json", utf8("{not-json"));
        Path pack = createPack(tempDir.resolve("broken-atlas.zip"), entries);

        IOException error = assertThrows(IOException.class, () -> runMix(tempDir, pack));
        assertTrue(normalizedMessage(error).contains("assets/minecraft/atlases/blocks.json"), error::getMessage);
    }

    @Test
    void preexistingInternalNamespaceFailsBeforeAnyGeneratedWrite(@TempDir Path tempDir) throws Exception {
        Map<String, byte[]> entries = minimalUiDirectoryPack();
        entries.put("assets/custom/textures/ui/tiny.png", png(1, 1));
        entries.put("assets/resourcepackmanager_internal/textures/user_owned.png", png(16, 16));
        Path pack = createPack(tempDir.resolve("internal-collision.zip"), entries);

        IOException error = assertThrows(IOException.class, () -> runMix(tempDir, pack));
        assertTrue(normalizedMessage(error).contains("assets/resourcepackmanager_internal"), error::getMessage);
    }

    @Test
    void emptyDirectorySourceScansFromTheTextureRoot(@TempDir Path tempDir) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("pack.mcmeta", utf8("""
                {"pack":{"pack_format":75,"description":"texture-root directory test"}}
                """));
        entries.put("assets/minecraft/atlases/blocks.json", utf8("""
                {"sources":[{"type":"directory","source":"","prefix":"all/"}]}
                """));
        entries.put("assets/custom/textures/root.png", png(16, 16));
        entries.put("assets/custom/textures/ui/icon.png", png(16, 16));

        MixOutput output = runMix(tempDir, createPack(tempDir.resolve("texture-root.zip"), entries));
        JsonArray sources = readJson(output.mergedDir().toPath()
                .resolve("assets/minecraft/atlases/blocks.json")).getAsJsonArray("sources");
        assertEquals(JsonParser.parseString("""
                [
                  {"type":"minecraft:single","resource":"custom:root","sprite":"custom:all/root"},
                  {"type":"minecraft:single","resource":"custom:ui/icon","sprite":"custom:all/ui/icon"}
                ]
                """).getAsJsonArray(), sources);
    }

    private static MixOutput runMix(Path tempDir, Path pack) throws Exception {
        return new MixEngine(new SilentLogger(), () -> false).run(new MixInput(
                List.of(pack.toFile()),
                tempDir.resolve("working").toFile(),
                tempDir.resolve("output").toFile(),
                tempDir.resolve("logs").toFile(),
                "merged",
                false));
    }

    private static Map<String, byte[]> minimalUiDirectoryPack() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("pack.mcmeta", utf8("""
                {"pack":{"pack_format":75,"description":"invalid atlas input test"}}
                """));
        entries.put("assets/minecraft/atlases/blocks.json", utf8("""
                {"sources":[{"type":"directory","source":"ui","prefix":"ui/"}]}
                """));
        return entries;
    }

    private static String normalizedMessage(Throwable error) {
        return String.valueOf(error.getMessage()).replace('\\', '/');
    }

    private static Path createPack(Path path, Map<String, byte[]> entries) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return path;
    }

    private static JsonObject readJson(Path path) throws Exception {
        try (var reader = Files.newBufferedReader(path)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int alpha = 0xFF << 24;
                int red = (x * 31 / Math.max(1, width - 1)) << 16;
                int green = (y * 47 / Math.max(1, height - 1)) << 8;
                image.setRGB(x, y, alpha | red | green | 0x5A);
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) throw new IllegalStateException("PNG writer unavailable");
        return output.toByteArray();
    }

    private static void assertImageSize(Path path, int expectedWidth, int expectedHeight) throws Exception {
        BufferedImage image = ImageIO.read(path.toFile());
        assertEquals(expectedWidth, image.getWidth(), path::toString);
        assertEquals(expectedHeight, image.getHeight(), path::toString);
    }

    private static void assertNearestNeighbour(Path originalPath, Path scaledPath) throws Exception {
        BufferedImage original = ImageIO.read(originalPath.toFile());
        BufferedImage scaled = ImageIO.read(scaledPath.toFile());
        for (int y = 0; y < scaled.getHeight(); y++) {
            for (int x = 0; x < scaled.getWidth(); x++) {
                int sourceX = (int) ((long) x * original.getWidth() / scaled.getWidth());
                int sourceY = (int) ((long) y * original.getHeight() / scaled.getHeight());
                assertEquals(original.getRGB(sourceX, sourceY), scaled.getRGB(x, y),
                        "Nearest-neighbour pixel mismatch at " + x + "," + y);
            }
        }
    }

    private static final class SilentLogger implements MixerLogger {
        @Override public void info(String message) { }
        @Override public void warn(String message) { }
        @Override public void collision(String message) { }
    }
}
