package com.magmaguy.resourcepackmanager.bedrock.converter;

import com.google.gson.*;
import com.magmaguy.resourcepackmanager.bedrock.BedrockLog;
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
     * Converts a bone model and writes the .geo.json file. FMM-style wrapper:
     * builds the FMM-prefixed geometry identifier and FMM-style output path,
     * then delegates to {@link #convertWithIdentifier(String, String, JsonObject, Map, int, int, File)}.
     *
     * @return geometry ID (e.g., "geometry.freeminecraftmodels.01_em_wolf.bodyback"), or null on failure
     */
    public static String convert(String modelName, String boneName, JsonObject javaModel,
                                 Map<String, SpriteInfo> spriteMap, int atlasWidth, int atlasHeight,
                                 File bedrockPackDir) {
        return convertWithIdentifier(
                "geometry.freeminecraftmodels." + modelName + "." + boneName,
                modelName + "/" + boneName,
                javaModel, spriteMap, atlasWidth, atlasHeight, bedrockPackDir);
    }

    /**
     * Generic-pipeline conversion: caller supplies the full geometry identifier
     * and output-path stem, so this method has no hardcoded "freeminecraftmodels"
     * naming. Added in Phase 6 of the generic Java&rarr;Bedrock pipeline.
     *
     * @param geometryIdentifier full Bedrock geometry identifier, e.g.
     *                           {@code "geometry.elitemobs.gear_bronze_sword"}
     * @param outputModelPath    stem under {@code models/entity/} (no extension);
     *                           e.g. {@code "elitemobs/gear_bronze_sword"} writes
     *                           to {@code models/entity/elitemobs/gear_bronze_sword.geo.json}
     * @return {@code geometryIdentifier} on success, {@code null} on failure
     */
    public static String convertWithIdentifier(String geometryIdentifier,
                                               String outputModelPath,
                                               JsonObject javaModel,
                                               Map<String, SpriteInfo> spriteMap,
                                               int atlasWidth, int atlasHeight,
                                               File bedrockPackDir) {
        if (!javaModel.has("elements") || !javaModel.get("elements").isJsonArray()) {
            BedrockLog.warn("[BedrockConverter] No elements in model " + geometryIdentifier);
            return null;
        }

        JsonArray elements = javaModel.getAsJsonArray("elements");
        if (elements.isEmpty()) return null;

        String geometryId = geometryIdentifier;

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

        // Bone pivot = center of bounding box, X inverted. The visual Y offset
        // needed to compensate for FMM's Bedrock-only entity Y-lift is applied as
        // an animation translation in FmmAnimationGenerator (HEAD_BASE_POS_Y), NOT
        // as a pivot offset here — moving the pivot away from cube centre breaks
        // rotations.
        double pivotX = -((minX + maxX) / 2.0);
        double pivotY = (minY + maxY) / 2.0;
        double pivotZ = (minZ + maxZ) / 2.0;

        // Build bone — field order matches Rainbow's Bone.CODEC: name, binding, pivot, cubes
        JsonObject bone = new JsonObject();
        bone.addProperty("name", "bone");
        bone.addProperty("binding", "q.item_slot_to_bone_name(context.item_slot)");
        // Rainbow's codec omits pivot when it's (0,0,0); mirror that behavior.
        if (pivotX != 0 || pivotY != 0 || pivotZ != 0) {
            bone.add("pivot", toArray(pivotX, pivotY, pivotZ));
        }
        bone.add("cubes", cubes);

        JsonArray bones = new JsonArray();
        bones.add(bone);

        // Build description — field order matches Rainbow's GeometryInfo.CODEC:
        // identifier, visible_bounds_*, texture_width, texture_height
        JsonObject description = new JsonObject();
        description.addProperty("identifier", geometryId);
        description.addProperty("visible_bounds_width", 4.0);
        description.addProperty("visible_bounds_height", 4.0);
        description.add("visible_bounds_offset", toArray(0, 0.75, 0));
        description.addProperty("texture_width", atlasWidth);
        description.addProperty("texture_height", atlasHeight);

        // Build geometry
        JsonObject geoEntry = new JsonObject();
        geoEntry.add("description", description);
        geoEntry.add("bones", bones);

        JsonArray geoArray = new JsonArray();
        geoArray.add(geoEntry);

        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.21.0");
        root.add("minecraft:geometry", geoArray);

        // Write file
        File geoFile = new File(bedrockPackDir, "models/entity/" + outputModelPath + ".geo.json");
        try {
            Files.createDirectories(geoFile.getParentFile().toPath());
            try (FileWriter writer = new FileWriter(geoFile, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            return geometryId;
        } catch (IOException e) {
            BedrockLog.warn("[BedrockConverter] Failed to write geometry " + geoFile.getPath() + ": " + e.getMessage());
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

        // Invert X axis
        originX = -(originX + sizeX);

        JsonObject cube = new JsonObject();
        cube.add("origin", toArray(originX, originY, originZ));
        cube.add("size", toArray(sizeX, sizeY, sizeZ));

        // Rotation — field order matches Rainbow's Cube.CODEC: rotation, then pivot.
        // Rainbow's codec elides both zero-rotation and zero-pivot via defaultToZeroCodec
        // (BedrockGeometry.java:221, 291-294); we mirror that here.
        if (element.has("rotation") && element.get("rotation").isJsonObject()) {
            JsonObject javaRot = element.getAsJsonObject("rotation");
            double angle = javaRot.has("angle") ? javaRot.get("angle").getAsDouble() : 0;
            String axis = javaRot.has("axis") ? javaRot.get("axis").getAsString() : "y";

            if (angle != 0) {
                JsonArray rotation = new JsonArray();
                switch (axis) {
                    case "x" -> { rotation.add(-angle); rotation.add(0); rotation.add(0); }
                    case "y" -> { rotation.add(0); rotation.add(angle); rotation.add(0); }
                    case "z" -> { rotation.add(0); rotation.add(0); rotation.add(angle); }
                    default  -> { rotation.add(0); rotation.add(0); rotation.add(0); }
                }
                cube.add("rotation", rotation);
            }

            if (javaRot.has("origin") && javaRot.get("origin").isJsonArray()) {
                JsonArray javaOrigin = javaRot.getAsJsonArray("origin");
                double px = javaOrigin.get(0).getAsDouble() - CENTRE_X;
                double py = javaOrigin.get(1).getAsDouble() - CENTRE_Y;
                double pz = javaOrigin.get(2).getAsDouble() - CENTRE_Z;
                px = -px;
                if (px != 0 || py != 0 || pz != 0) {
                    cube.add("pivot", toArray(px, py, pz));
                }
            }
        }

        // UV faces
        if (element.has("faces") && element.get("faces").isJsonObject()) {
            JsonObject javaFaces = element.getAsJsonObject("faces");
            JsonObject bedrockUV = new JsonObject();

            // Use the original (pre-centring) from/to for default UV computation,
            // matching vanilla FaceBakery#defaultFaceUV which operates in raw 0..16 space.
            double rawFromX = from.get(0).getAsDouble();
            double rawFromY = from.get(1).getAsDouble();
            double rawFromZ = from.get(2).getAsDouble();
            double rawToX = to.get(0).getAsDouble();
            double rawToY = to.get(1).getAsDouble();
            double rawToZ = to.get(2).getAsDouble();

            for (String faceName : new String[]{"north", "east", "south", "west", "up", "down"}) {
                if (!javaFaces.has(faceName) || !javaFaces.get(faceName).isJsonObject()) continue;
                JsonObject javaFace = javaFaces.getAsJsonObject(faceName);

                // Resolve UV: use explicit uv array if present, else fall back to the
                // vanilla FaceBakery default derived from from/to. (Rainbow does the same:
                // GeometryMapper.java:90-93 -> FaceBakeryAccessor.invokeDefaultFaceUV.)
                double[] uvVals;
                if (javaFace.has("uv") && javaFace.get("uv").isJsonArray()) {
                    JsonArray javaUV = javaFace.getAsJsonArray("uv");
                    if (javaUV.size() < 4) continue;
                    uvVals = new double[]{
                            javaUV.get(0).getAsDouble(),
                            javaUV.get(1).getAsDouble(),
                            javaUV.get(2).getAsDouble(),
                            javaUV.get(3).getAsDouble()
                    };
                } else {
                    uvVals = defaultFaceUV(faceName,
                            rawFromX, rawFromY, rawFromZ,
                            rawToX, rawToY, rawToZ);
                }

                String texRef = javaFace.has("texture") ? javaFace.get("texture").getAsString() : "#0";
                String texIndex = texRef.startsWith("#") ? texRef.substring(1) : texRef;

                int faceRotation = 0;
                if (javaFace.has("rotation") && javaFace.get("rotation").isJsonPrimitive()) {
                    int raw = javaFace.get("rotation").getAsInt();
                    // Rainbow's Quadrant.CODEC rejects non-quadrant values upstream; mirror
                    // that strictness with a warn-and-skip rather than silent acceptance.
                    int normalized = ((raw % 360) + 360) % 360;
                    if (normalized % 90 != 0) {
                        // Per-face rotation note — fires once per off-quadrant face in
                        // the source model. Cosmetic (the face still emits without
                        // rotation), so demote to debug.
                        BedrockLog.debug("[BedrockConverter] face rotation " + raw + " on " + faceName
                                + " is not a multiple of 90; ignoring (Rainbow parity).");
                    } else {
                        faceRotation = normalized;
                    }
                }

                boolean isUpDown = faceName.equals("up") || faceName.equals("down");
                JsonObject faceUV = convertFaceUV(
                        uvVals, isUpDown,
                        spriteMap.get(texIndex),
                        faceRotation
                );
                if (faceUV != null) bedrockUV.add(faceName, faceUV);
            }

            if (bedrockUV.size() > 0) {
                cube.add("uv", bedrockUV);
            }
        }

        return cube;
    }

    /**
     * Vanilla Minecraft FaceBakery#defaultFaceUV equivalent: derive face UVs from the
     * cube's from/to coordinates when the model JSON omits a face's `uv` array.
     * All inputs are in the raw 0..16 space (not the centred bedrock space).
     */
    private static double[] defaultFaceUV(String face,
                                          double fromX, double fromY, double fromZ,
                                          double toX, double toY, double toZ) {
        return switch (face) {
            case "north" -> new double[]{16 - toX, 16 - toY, 16 - fromX, 16 - fromY};
            case "south" -> new double[]{fromX, 16 - toY, toX, 16 - fromY};
            case "west"  -> new double[]{fromZ, 16 - toY, toZ, 16 - fromY};
            case "east"  -> new double[]{16 - toZ, 16 - toY, 16 - fromZ, 16 - fromY};
            case "up"    -> new double[]{fromX, fromZ, toX, toZ};
            case "down"  -> new double[]{fromX, 16 - toZ, toX, 16 - fromZ};
            default      -> new double[]{0, 0, 16, 16};
        };
    }

    private static JsonObject convertFaceUV(double[] javaUV, boolean flipUpDown, SpriteInfo sprite,
                                            int faceRotation) {
        double u1 = javaUV[0];
        double v1 = javaUV[1];
        double u2 = javaUV[2];
        double v2 = javaUV[3];

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

        // Per-face UV rotation (Rainbow: GeometryMapper.java:113, BedrockGeometry.java:286).
        // Java stores rotation as a multiple of 90 (0/90/180/270); emit only when non-zero.
        // format_version 1.21.0 (above) is required for Bedrock to parse this field.
        if (faceRotation != 0) {
            int normalized = ((faceRotation % 360) + 360) % 360;
            if (normalized != 0) {
                faceUV.addProperty("uv_rotation", normalized);
            }
        }
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
