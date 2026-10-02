package com.magmaguy.resourcepackmanager.bedrock.converter;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ItemParticleAttachmentTest {
    @TempDir
    Path directory;

    private File javaPack() throws Exception {
        Path sidecar = directory.resolve("java/assets/freeminecraftmodels/rspm_item_particles/display/wand.json");
        Files.createDirectories(sidecar.getParent());
        Files.writeString(sidecar, """
                {"effects": {"wand_particles": "fmm:wand_wand_particles"},
                 "locators": {"locator": [8.0, 14.0, 24.0]},
                 "length": 2.0,
                 "particle_effects": {"0": {"effect": "wand_particles", "locator": "locator"}}}""");
        return directory.resolve("java").toFile();
    }

    @Test
    void modelsWithoutASidecarGetNothing() throws Exception {
        assertNull(ItemParticleAttachment.prepare("freeminecraftmodels:display/staff", javaPack(), "rspm.abc", "abc",
                directory.resolve("bedrock").toFile()));
    }

    @Test
    void writesALoopingParticleAnimation() throws Exception {
        File bedrock = directory.resolve("bedrock").toFile();
        assertNotNull(ItemParticleAttachment.prepare("freeminecraftmodels:display/wand", javaPack(), "rspm.abc", "abc", bedrock));
        JsonObject animation = JsonParser.parseString(Files.readString(bedrock.toPath().resolve("animations/abc.particles.animation.json")))
                .getAsJsonObject().getAsJsonObject("animations").getAsJsonObject("animation.rspm.abc.particles");
        assertTrue(animation.get("loop").getAsBoolean());
        assertEquals(2.0, animation.get("animation_length").getAsDouble());
        assertEquals("wand_particles", animation.getAsJsonObject("particle_effects").getAsJsonObject("0").get("effect").getAsString());
    }

    @Test
    void addsLocatorsInGeometrySpaceAndWiresTheAttachable() throws Exception {
        File bedrock = directory.resolve("bedrock").toFile();
        ItemParticleAttachment particles = ItemParticleAttachment.prepare("freeminecraftmodels:display/wand", javaPack(),
                "rspm.abc", "abc", bedrock);
        Path geometry = directory.resolve("bedrock/models/entity/abc.geo.json");
        Files.createDirectories(geometry.getParent());
        Files.writeString(geometry, """
                {"format_version": "1.16.0", "minecraft:geometry": [{"description": {"identifier": "geometry.rspm.abc"},
                  "bones": [{"name": "bone", "cubes": []}]}]}""");
        particles.addLocators(geometry.toFile());
        JsonArray locator = JsonParser.parseString(Files.readString(geometry)).getAsJsonObject()
                .getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject().getAsJsonArray("bones").get(0)
                .getAsJsonObject().getAsJsonObject("locators").getAsJsonArray("locator");
        assertEquals("[-0.0,14.0,16.0]", locator.toString(), "Centred on 8, 0, 8 with X mirrored like the cubes");

        JsonObject description = JsonParser.parseString("""
                {"animations": {"first_person": "a"}, "scripts": {"animate": [{"first_person": "1"}]}}""").getAsJsonObject();
        particles.applyTo(description);
        assertEquals("fmm:wand_wand_particles", description.getAsJsonObject("particle_effects").get("wand_particles").getAsString());
        assertEquals("animation.rspm.abc.particles", description.getAsJsonObject("animations").get("particles").getAsString());
        assertEquals("particles", description.getAsJsonObject("scripts").getAsJsonArray("animate").get(1).getAsString());
    }
}
