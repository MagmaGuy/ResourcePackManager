package com.magmaguy.resourcepackmanager.bedrock.converter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Converts models through the real geometry and animation writers, then renders every cube
 * corner twice in Java's first-person view space: once through Java's held-item transform, once
 * through the emitted Bedrock bone on the vanilla first-person rig. The two must coincide.
 */
class FirstPersonTransformTest {
    // Java: ItemInHandRenderer.applyItemArmTransform for the right hand, fully equipped.
    private static final double[] JAVA_HAND = {0.56, -0.52, -0.72};
    // Bedrock: animation.player.first_person.empty_hand on geometry.humanoid.custom, the
    // attachable binding offset, and Blockbench's first-person attachable camera.
    private static final double[] ARM_PIVOT = {-5, 22, 0};
    private static final double[] ARM_POSITION = {13.5, -10, 12};
    private static final double[] ARM_ROTATION = {95, -45, 115};
    private static final double[] ITEM_PIVOT = {-6, 15, 1};
    private static final double[] ITEM_POSITION = {0, 0, -1};
    private static final double[] BINDING_ORIGIN = {0, 24, 0};
    private static final double[] CAMERA = {0, 27.41, 0};

    @TempDir
    Path directory;

    @Test
    void dalisWandLandsWhereJavaDrawsIt() throws Exception {
        assertBedrockMatchesJava(wand("[0.6, 0.6, 0.6]"));
    }

    @Test
    void scalingAnOffCentreModelMovesItLikeJava() throws Exception {
        double[] small = assertBedrockMatchesJava(wand("[0.3, 0.3, 0.3]"));
        double[] large = assertBedrockMatchesJava(wand("[0.9, 0.9, 0.9]"));
        double[] moved = subtract(large, small);
        assertTrue(Math.sqrt(moved[0] * moved[0] + moved[1] * moved[1] + moved[2] * moved[2]) > 3,
                "Java scales about the block centre, so the bone position must move");
    }

    @Test
    void handheldStickMatchesJava() throws Exception {
        assertBedrockMatchesJava("""
                {"elements": [{"from": [7, 0, 7], "to": [9, 16, 9]}],
                 "display": {"firstperson_righthand":
                   {"rotation": [0, -90, 25], "translation": [1.13, 3.2, 1.13], "scale": [0.4, 0.4, 0.4]}}}""");
    }

    @Test
    void modelWithoutFirstPersonDisplayUsesJavasIdentityPose() throws Exception {
        assertBedrockMatchesJava("""
                {"elements": [{"from": [4, 2, 6], "to": [12, 20, 9]}]}""");
    }

    @Test
    void nonUniformScaleKeepsEachAxis() throws Exception {
        assertBedrockMatchesJava("""
                {"elements": [{"from": [0, 0, 7.5], "to": [16, 16, 8.5]}],
                 "display": {"firstperson_righthand":
                   {"rotation": [-20, 90, -35], "translation": [3.13, 2, 0.13], "scale": [1.36, 1.36, 0.68]}}}""");
    }

    private static String wand(String scale) {
        return """
                {"elements": [
                   {"from": [7, 12.25, -2.75], "to": [9, 14.25, 22]},
                   {"from": [6.2, 12.25, 22], "to": [9.75, 15.75, 25.34]}],
                 "display": {"firstperson_righthand":
                   {"rotation": [-90, 0, 0], "translation": [1.25, -1, 6], "scale": %s}}}""".formatted(scale);
    }

    /** @return the emitted first-person bone position, for callers comparing poses */
    private double[] assertBedrockMatchesJava(String modelJson) throws Exception {
        JsonObject model = JsonParser.parseString(modelJson).getAsJsonObject();
        File pack = Files.createTempDirectory(directory, "pack").toFile();
        assertNotNull(FmmGeometryConverter.convertWithIdentifier("geometry.rspm.test", "test", model,
                Map.of(), 16, 16, pack));
        assertNotNull(FmmAttachableGenerator.prepareAnimations("rspm.test", "test", model, pack));

        JsonObject bone = JsonParser.parseString(Files.readString(pack.toPath().resolve("models/entity/test.geo.json")))
                .getAsJsonObject().getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject()
                .getAsJsonArray("bones").get(0).getAsJsonObject();
        JsonObject pose = JsonParser.parseString(Files.readString(pack.toPath().resolve("animations/test.animation.json")))
                .getAsJsonObject().getAsJsonObject("animations")
                .getAsJsonObject("animation.rspm.test.hold_first_person").getAsJsonObject("bones").getAsJsonObject("bone");
        double[] pivot = vector(bone.get("pivot"), 0);
        double[] position = vector(pose.get("position"), 0);
        double[] rotation = vector(pose.get("rotation"), 0);
        double[] scale = vector(pose.get("scale"), 1);

        JsonObject display = model.has("display")
                ? model.getAsJsonObject("display").getAsJsonObject("firstperson_righthand") : new JsonObject();
        double[] javaTranslation = vector(display.get("translation"), 0);
        double[] javaRotation = vector(display.get("rotation"), 0);
        double[] javaScale = vector(display.get("scale"), 1);

        for (JsonElement cubeElement : bone.getAsJsonArray("cubes")) {
            JsonObject cube = cubeElement.getAsJsonObject();
            double[] origin = vector(cube.get("origin"), 0);
            double[] size = vector(cube.get("size"), 0);
            for (int corner = 0; corner < 8; corner++) {
                double[] geometry = {
                        origin[0] + ((corner & 1) == 0 ? 0 : size[0]),
                        origin[1] + ((corner & 2) == 0 ? 0 : size[1]),
                        origin[2] + ((corner & 4) == 0 ? 0 : size[2])};
                double[] javaVertex = {8 - geometry[0], geometry[1], geometry[2] + 8};
                assertArrayEquals(javaView(javaVertex, javaTranslation, javaRotation, javaScale),
                        bedrockView(geometry, pivot, position, rotation, scale), 1e-4,
                        "corner " + corner + " of cube at " + cube.get("origin"));
            }
        }
        return position;
    }

    private static double[] javaView(double[] vertex, double[] translation, double[] rotation, double[] scale) {
        double[] centred = {vertex[0] / 16 - 0.5, vertex[1] / 16 - 0.5, vertex[2] / 16 - 0.5};
        double[] turned = apply(multiply(rotX(rotation[0]), multiply(rotY(rotation[1]), rotZ(rotation[2]))),
                multiplyEach(scale, centred));
        return add(add(JAVA_HAND, scaled(translation, 1 / 16.0)), turned);
    }

    /** Bedrock files mirror X; everything below runs in the rendered frame. */
    private static double[] bedrockView(double[] geometry, double[] pivot, double[] position,
                                        double[] rotation, double[] scale) {
        double[] p = mirror(pivot);
        double[] point = add(add(mirror(position), p),
                apply(bedrockRotation(rotation), multiplyEach(scale, subtract(mirror(geometry), p))));
        point = add(point, subtract(add(mirror(ITEM_PIVOT), mirror(ITEM_POSITION)), BINDING_ORIGIN));
        double[] armPivot = mirror(ARM_PIVOT);
        point = add(add(apply(bedrockRotation(ARM_ROTATION), subtract(point, armPivot)), armPivot),
                mirror(ARM_POSITION));
        return scaled(apply(rotY(180), subtract(point, CAMERA)), 1 / 16.0);
    }

    private static double[][] bedrockRotation(double[] r) {
        return multiply(rotZ(r[2]), multiply(rotY(-r[1]), rotX(-r[0])));
    }

    private static double[] vector(JsonElement element, double fallback) {
        if (element == null) return new double[]{fallback, fallback, fallback};
        JsonArray array = element.getAsJsonArray();
        return new double[]{array.get(0).getAsDouble(), array.get(1).getAsDouble(), array.get(2).getAsDouble()};
    }

    private static double[] mirror(double[] v) {
        return new double[]{-v[0], v[1], v[2]};
    }

    private static double[][] rotX(double degrees) {
        double c = Math.cos(Math.toRadians(degrees)), s = Math.sin(Math.toRadians(degrees));
        return new double[][]{{1, 0, 0}, {0, c, -s}, {0, s, c}};
    }

    private static double[][] rotY(double degrees) {
        double c = Math.cos(Math.toRadians(degrees)), s = Math.sin(Math.toRadians(degrees));
        return new double[][]{{c, 0, s}, {0, 1, 0}, {-s, 0, c}};
    }

    private static double[][] rotZ(double degrees) {
        double c = Math.cos(Math.toRadians(degrees)), s = Math.sin(Math.toRadians(degrees));
        return new double[][]{{c, -s, 0}, {s, c, 0}, {0, 0, 1}};
    }

    private static double[][] multiply(double[][] a, double[][] b) {
        double[][] out = new double[3][3];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                for (int k = 0; k < 3; k++) out[i][j] += a[i][k] * b[k][j];
        return out;
    }

    private static double[] apply(double[][] m, double[] v) {
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) out[i] = m[i][0] * v[0] + m[i][1] * v[1] + m[i][2] * v[2];
        return out;
    }

    private static double[] add(double[] a, double[] b) {
        return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    private static double[] subtract(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double[] multiplyEach(double[] a, double[] b) {
        return new double[]{a[0] * b[0], a[1] * b[1], a[2] * b[2]};
    }

    private static double[] scaled(double[] v, double factor) {
        return new double[]{v[0] * factor, v[1] * factor, v[2] * factor};
    }
}
