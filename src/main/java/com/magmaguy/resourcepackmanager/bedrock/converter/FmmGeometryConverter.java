package com.magmaguy.resourcepackmanager.bedrock.converter;

import com.google.gson.*;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.bedrock.model.SpriteInfo;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

/**
 * Converts a single FMM bone model (Java Edition JSON) to Bedrock geometry (.geo.json).
 */
public class FmmGeometryConverter {

    private static final double CENTRE_X = 8.0;
    private static final double CENTRE_Y = 0.0;
    private static final double CENTRE_Z = 8.0;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /**
     * Converts a bone model and writes the .geo.json file.
     *
     * @return geometry ID (e.g., "geometry.freeminecraftmodels.01_em_wolf.bodyback"), or null on failure
     */
    public static String convert(String modelName, String boneName, JsonObject javaModel,
                                 Map<String, SpriteInfo> spriteMap, int atlasWidth, int atlasHeight,
                                 File bedrockPackDir) {
        if (!javaModel.has("elements") || !javaModel.get("elements").isJsonArray()) {
            Logger.warn("[BedrockConverter] No elements in bone model " + modelName + "/" + boneName);
            return null;
        }

        JsonArray elements = javaModel.getAsJsonArray("elements");
        if (elements.isEmpty()) return null;

        String geometryId = "geometry.freeminecraftmodels." + modelName + "." + boneName;

        JsonArray cubes = new JsonArray();
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;

        for (JsonElement el : elements) {
            if (!el.isJsonObject()) continue;
            JsonObject element = el.getAsJsonObject();
            if (!element.has("from") || !element.has("to")) continue;

            JsonObject cube = convertElement(element, spriteMap, atlasWidth, atlasHeight);
            if (cube == null) continue;
            cubes.add(cube);

            // Track bounding box in centred coordinates
            JsonArray from = element.getAsJsonArray("from");
            JsonArray to = element.getAsJsonArray("to");
            double fx = from.get(0).getAsDouble() - CENTRE_X;
            double fy = from.get(1).getAsDouble() - CENTRE_Y;
            double fz = from.get(2).getAsDouble() - CENTRE_Z;
            double tx = to.get(0).getAsDouble() - CENTRE_X;
            double ty = to.get(1).getAsDouble() - CENTRE_Y;
            double tz = to.get(2).getAsDouble() - CENTRE_Z;
            minX = Math.min(minX, Math.min(fx, tx));
            minY = Math.min(minY, Math.min(fy, ty));
            minZ = Math.min(minZ, Math.min(fz, tz));
            maxX = Math.max(maxX, Math.max(fx, tx));
            maxY = Math.max(maxY, Math.max(fy, ty));
            maxZ = Math.max(maxZ, Math.max(fz, tz));
        }

        if (cubes.isEmpty()) return null;

        // Bone pivot = center of bounding box, X inverted
        double pivotX = -((minX + maxX) / 2.0);
        double pivotY = (minY + maxY) / 2.0;
        double pivotZ = (minZ + maxZ) / 2.0;

        // Build bone
        JsonObject bone = new JsonObject();
        bone.addProperty("name", "bone");
        bone.add("pivot", toArray(pivotX, pivotY, pivotZ));
        bone.addProperty("binding", "q.item_slot_to_bone_name(context.item_slot)");
        bone.add("cubes", cubes);

        JsonArray bones = new JsonArray();
        bones.add(bone);

        // Build description
        JsonObject description = new JsonObject();
        description.addProperty("identifier", geometryId);
        description.addProperty("texture_width", atlasWidth);
        description.addProperty("texture_height", atlasHeight);
        description.addProperty("visible_bounds_width", 4.0);
        description.addProperty("visible_bounds_height", 4.0);
        description.add("visible_bounds_offset", toArray(0, 0.75, 0));

        // Build geometry
        JsonObject geoEntry = new JsonObject();
        geoEntry.add("description", description);
        geoEntry.add("bones", bones);

        JsonArray geoArray = new JsonArray();
        geoArray.add(geoEntry);

        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.16.0");
        root.add("minecraft:geometry", geoArray);

        // Write file
        File geoFile = new File(bedrockPackDir, "models/entity/" + modelName + "/" + boneName + ".geo.json");
        try {
            Files.createDirectories(geoFile.getParentFile().toPath());
            try (FileWriter writer = new FileWriter(geoFile, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            return geometryId;
        } catch (IOException e) {
            Logger.warn("[BedrockConverter] Failed to write geometry " + geoFile.getPath() + ": " + e.getMessage());
            return null;
        }
    }

    private static JsonObject convertElement(JsonObject element, Map<String, SpriteInfo> spriteMap,
                                             int atlasWidth, int atlasHeight) {
        JsonArray from = element.getAsJsonArray("from");
        JsonArray to = element.getAsJsonArray("to");

        double fromX = from.get(0).getAsDouble() - CENTRE_X;
        double fromY = from.get(1).getAsDouble() - CENTRE_Y;
        double fromZ = from.get(2).getAsDouble() - CENTRE_Z;
        double toX = to.get(0).getAsDouble() - CENTRE_X;
        double toY = to.get(1).getAsDouble() - CENTRE_Y;
        double toZ = to.get(2).getAsDouble() - CENTRE_Z;

        double originX = Math.min(fromX, toX);
        double originY = Math.min(fromY, toY);
        double originZ = Math.min(fromZ, toZ);
        double sizeX = Math.max(fromX, toX) - originX;
        double sizeY = Math.max(fromY, toY) - originY;
        double sizeZ = Math.max(fromZ, toZ) - originZ;

        if (sizeX == 0) sizeX = 0.01;
        if (sizeY == 0) sizeY = 0.01;
        if (sizeZ == 0) sizeZ = 0.01;

        // Invert X axis
        originX = -(originX + sizeX);

        JsonObject cube = new JsonObject();
        cube.add("origin", toArray(originX, originY, originZ));
        cube.add("size", toArray(sizeX, sizeY, sizeZ));

        // Rotation
        if (element.has("rotation") && element.get("rotation").isJsonObject()) {
            JsonObject javaRot = element.getAsJsonObject("rotation");
            double angle = javaRot.has("angle") ? javaRot.get("angle").getAsDouble() : 0;
            String axis = javaRot.has("axis") ? javaRot.get("axis").getAsString() : "y";

            if (angle != 0) {
                if (javaRot.has("origin") && javaRot.get("origin").isJsonArray()) {
                    JsonArray javaOrigin = javaRot.getAsJsonArray("origin");
                    double px = javaOrigin.get(0).getAsDouble() - CENTRE_X;
                    double py = javaOrigin.get(1).getAsDouble() - CENTRE_Y;
                    double pz = javaOrigin.get(2).getAsDouble() - CENTRE_Z;
                    px = -px;
                    cube.add("pivot", toArray(px, py, pz));
                }

                JsonArray rotation = new JsonArray();
                switch (axis) {
                    case "x" -> { rotation.add(-angle); rotation.add(0); rotation.add(0); }
                    case "y" -> { rotation.add(0); rotation.add(angle); rotation.add(0); }
                    case "z" -> { rotation.add(0); rotation.add(0); rotation.add(-angle); }
                    default  -> { rotation.add(0); rotation.add(0); rotation.add(0); }
                }
                cube.add("rotation", rotation);
            }
        }

        // UV faces
        if (element.has("faces") && element.get("faces").isJsonObject()) {
            JsonObject javaFaces = element.getAsJsonObject("faces");
            JsonObject bedrockUV = new JsonObject();

            for (String faceName : new String[]{"north", "east", "south", "west", "up", "down"}) {
                if (!javaFaces.has(faceName) || !javaFaces.get(faceName).isJsonObject()) continue;
                JsonObject javaFace = javaFaces.getAsJsonObject(faceName);
                if (!javaFace.has("uv") || !javaFace.get("uv").isJsonArray()) continue;

                String texRef = javaFace.has("texture") ? javaFace.get("texture").getAsString() : "#0";
                String texIndex = texRef.startsWith("#") ? texRef.substring(1) : texRef;

                boolean isUpDown = faceName.equals("up") || faceName.equals("down");
                JsonObject faceUV = convertFaceUV(
                        javaFace.getAsJsonArray("uv"), isUpDown,
                        spriteMap.get(texIndex)
                );
                if (faceUV != null) bedrockUV.add(faceName, faceUV);
            }

            if (bedrockUV.size() > 0) {
                cube.add("uv", bedrockUV);
            }
        }

        return cube;
    }

    private static JsonObject convertFaceUV(JsonArray javaUV, boolean flipUpDown, SpriteInfo sprite) {
        if (javaUV.size() < 4) return null;

        double u1 = javaUV.get(0).getAsDouble();
        double v1 = javaUV.get(1).getAsDouble();
        double u2 = javaUV.get(2).getAsDouble();
        double v2 = javaUV.get(3).getAsDouble();

        // Scale from [0, 16] to sprite pixel coordinates and offset by atlas position
        double scaleX = 1.0;
        double scaleY = 1.0;
        double offsetX = 0;
        double offsetY = 0;

        if (sprite != null) {
            scaleX = sprite.width() / 16.0;
            scaleY = sprite.height() / 16.0;
            offsetX = sprite.x();
            offsetY = sprite.y();
        }

        u1 = u1 * scaleX + offsetX;
        v1 = v1 * scaleY + offsetY;
        u2 = u2 * scaleX + offsetX;
        v2 = v2 * scaleY + offsetY;

        JsonObject faceUV = new JsonObject();
        JsonArray uv = new JsonArray();
        JsonArray uvSize = new JsonArray();

        if (flipUpDown) {
            uv.add(u2);
            uv.add(v2);
            uvSize.add(u1 - u2);
            uvSize.add(v1 - v2);
        } else {
            uv.add(u1);
            uv.add(v1);
            uvSize.add(u2 - u1);
            uvSize.add(v2 - v1);
        }

        faceUV.add("uv", uv);
        faceUV.add("uv_size", uvSize);
        return faceUV;
    }

    private static JsonArray toArray(double x, double y, double z) {
        JsonArray arr = new JsonArray();
        arr.add(round4(x));
        arr.add(round4(y));
        arr.add(round4(z));
        return arr;
    }

    private static double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
