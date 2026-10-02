package com.magmaguy.resourcepackmanager.bedrock.converter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.magmaguy.resourcepackmanager.bedrock.BedrockLog;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

/**
 * Particles for a held item's Bedrock attachable. A producer plugin (FreeMinecraftModels) writes
 * {@code assets/<namespace>/rspm_item_particles/<model path>.json} beside the Java item model:
 * effect short names mapped to particle identifiers it ships in its Bedrock bundle, locators in
 * Java item model pixels, and a looping particle timeline. This adds the locators to the item
 * geometry, writes the timeline as an attachable animation, and wires both into the attachable,
 * so the Bedrock client draws the particles at the real hand in first and third person.
 */
public final class ItemParticleAttachment {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final double CENTRE_X = 8.0;
    private static final double CENTRE_Z = 8.0;

    private final JsonObject effects;
    private final JsonObject locators;
    private final String animationId;

    private ItemParticleAttachment(JsonObject effects, JsonObject locators, String animationId) {
        this.effects = effects;
        this.locators = locators;
        this.animationId = animationId;
    }

    /**
     * Reads the sidecar for a Java model reference and writes its particle animation.
     *
     * @param modelRef    Java model reference such as {@code freeminecraftmodels:display/wand}
     * @param animBaseId  identifier base shared with the model's other attachable animations
     * @return the attachment, or null when the model has no particle sidecar or it is unreadable
     */
    public static ItemParticleAttachment prepare(String modelRef, File mergedJavaPack, String animBaseId,
                                                 String animFileBase, File bedrockPackDir) {
        File sidecar = sidecarFile(modelRef, mergedJavaPack);
        if (sidecar == null || !sidecar.isFile()) return null;
        try {
            JsonObject json = JsonParser.parseString(Files.readString(sidecar.toPath(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonObject effects = json.getAsJsonObject("effects");
            JsonObject timeline = json.getAsJsonObject("particle_effects");
            if (effects == null || effects.entrySet().isEmpty() || timeline == null) return null;

            JsonObject locators = new JsonObject();
            JsonObject javaLocators = json.has("locators") ? json.getAsJsonObject("locators") : new JsonObject();
            for (Map.Entry<String, JsonElement> entry : javaLocators.entrySet()) {
                JsonArray java = entry.getValue().getAsJsonArray();
                // Same centring and X mirror FmmGeometryConverter applies to the item's cubes.
                JsonArray bedrock = new JsonArray();
                bedrock.add(-(java.get(0).getAsDouble() - CENTRE_X));
                bedrock.add(java.get(1).getAsDouble());
                bedrock.add(java.get(2).getAsDouble() - CENTRE_Z);
                locators.add(entry.getKey(), bedrock);
            }

            String animationId = "animation." + animBaseId + ".particles";
            JsonObject animation = new JsonObject();
            animation.addProperty("loop", true);
            animation.addProperty("animation_length", json.has("length") ? json.get("length").getAsDouble() : 1.0);
            animation.add("particle_effects", timeline);
            JsonObject animations = new JsonObject();
            animations.add(animationId, animation);
            JsonObject root = new JsonObject();
            root.addProperty("format_version", "1.8.0");
            root.add("animations", animations);
            File file = new File(bedrockPackDir, "animations/" + animFileBase + ".particles.animation.json");
            Files.createDirectories(file.getParentFile().toPath());
            Files.writeString(file.toPath(), GSON.toJson(root), StandardCharsets.UTF_8);
            return new ItemParticleAttachment(effects, locators, animationId);
        } catch (IOException | RuntimeException exception) {
            BedrockLog.warn("[BedrockConverter] Ignoring unreadable item particle sidecar " + sidecar + ": "
                    + exception.getMessage());
            return null;
        }
    }

    static File sidecarFile(String modelRef, File mergedJavaPack) {
        if (modelRef == null || mergedJavaPack == null) return null;
        int colon = modelRef.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : modelRef.substring(0, colon);
        String path = colon < 0 ? modelRef : modelRef.substring(colon + 1);
        if (path.contains("..") || namespace.contains("..")) return null;
        return new File(mergedJavaPack, "assets/" + namespace + "/rspm_item_particles/" + path + ".json");
    }

    /** Adds the locators to the item geometry's bones. */
    public void addLocators(File geometryFile) {
        if (locators.entrySet().isEmpty() || !geometryFile.isFile()) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(geometryFile.toPath(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            for (JsonElement geometry : root.getAsJsonArray("minecraft:geometry")) {
                JsonArray bones = geometry.getAsJsonObject().getAsJsonArray("bones");
                if (bones == null || bones.isEmpty()) continue;
                bones.get(0).getAsJsonObject().add("locators", locators.deepCopy());
            }
            Files.writeString(geometryFile.toPath(), GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException exception) {
            BedrockLog.warn("[BedrockConverter] Could not add particle locators to " + geometryFile + ": "
                    + exception.getMessage());
        }
    }

    /** Adds the effects and the always-playing particle animation to an attachable description. */
    public void applyTo(JsonObject description) {
        description.add("particle_effects", effects.deepCopy());
        description.getAsJsonObject("animations").addProperty("particles", animationId);
        description.getAsJsonObject("scripts").getAsJsonArray("animate").add("particles");
    }
}
