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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Converts models through the real geometry and animation writers, then renders every cube
 * corner twice: once through Java's held-item transform, once through the emitted Bedrock bone
 * on the vanilla Bedrock player rig. The two must coincide.
 *
 * <p>First person compares in Java's first-person view space. Third person compares in Bedrock's
 * rendered player model frame for several arm poses, since both editions hang the item off the
 * same arm.
 */
class HeldItemTransformTest {
    // Java: ItemInHandRenderer.applyItemArmTransform for the right hand, fully equipped.
    private static final double[] JAVA_VIEW_HAND = {0.56, -0.52, -0.72};
    // Java: HumanoidModel's right arm offset and ItemInHandLayer's offset from it, in pixels.
    private static final double[] JAVA_ARM_OFFSET = {-5, 2, 0};
    private static final double[] JAVA_HAND_OFFSET = {1, 2, -10};
    // Bedrock: geometry.humanoid.custom and the attachable binding offset.
    private static final double[] ARM_PIVOT = {-5, 22, 0};
    private static final double[] ITEM_PIVOT = {-6, 15, 1};
    private static final double[] BINDING_ORIGIN = {0, 24, 0};
    // Bedrock first person: animation.player.first_person.empty_hand and Blockbench's
    // first-person attachable camera.
    private static final double[] FIRST_PERSON_ARM_POSITION = {13.5, -10, 12};
    private static final double[] FIRST_PERSON_ARM_ROTATION = {95, -45, 115};
    private static final double[] FIRST_PERSON_ITEM_POSITION = {0, 0, -1};
    private static final double[] CAMERA = {0, 27.41, 0};
    // Right arm poses shared by both editions (Java part angles in degrees = Bedrock bone angles):
    // at rest, holding an item with the idle bob at its peak, and mid-swing.
    private static final double[][] ARM_POSES = {{0, 0, 0}, {-18, 0, 5.73}, {-41, 23, 7}};

    @TempDir
    Path directory;

    @Test
    void dalisWandLandsWhereJavaDrawsIt() throws Exception {
        assertFirstPersonMatchesJava(wand("[0.6, 0.6, 0.6]"));
        assertThirdPersonMatchesJava(wand("[0.6, 0.6, 0.6]"));
    }

    @Test
    void scalingAnOffCentreModelMovesItLikeJava() throws Exception {
        double[] small = assertFirstPersonMatchesJava(wand("[0.3, 0.3, 0.3]"));
        double[] large = assertFirstPersonMatchesJava(wand("[0.9, 0.9, 0.9]"));
        double[] moved = subtract(large, small);
        assertTrue(Math.sqrt(moved[0] * moved[0] + moved[1] * moved[1] + moved[2] * moved[2]) > 3,
                "Java scales about the block centre, so the bone position must move");
        assertThirdPersonMatchesJava(wand("[0.3, 0.3, 0.3]"));
        assertThirdPersonMatchesJava(wand("[0.9, 0.9, 0.9]"));
    }

    @Test
    void handheldStickMatchesJava() throws Exception {
        String stick = """
                {"elements": [{"from": [7, 0, 7], "to": [9, 16, 9]}],
                 "display": {
                   "firstperson_righthand":
                     {"rotation": [0, -90, 25], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]},
                   "thirdperson_righthand":
                     {"rotation": [0, -90, 55], "translation": [0, 4, 0.5], "scale": [0.85, 0.85, 0.85]}}}""";
        assertFirstPersonMatchesJava(stick);
        assertThirdPersonMatchesJava(stick);
    }

    @Test
    void modelWithoutDisplayUsesJavasIdentityPose() throws Exception {
        String model = """
                {"elements": [{"from": [4, 2, 6], "to": [12, 20, 9]}]}""";
        assertFirstPersonMatchesJava(model);
        assertThirdPersonMatchesJava(model);
    }

    @Test
    void nonUniformScaleKeepsEachAxis() throws Exception {
        String spear = """
                {"elements": [{"from": [0, 0, 7.5], "to": [16, 16, 8.5]}],
                 "display": {
                   "firstperson_righthand":
                     {"rotation": [-20, 90, -35], "translation": [3.13, 2, 0.13], "scale": [1.36, 1.36, 0.68]},
                   "thirdperson_righthand":
                     {"rotation": [5, 270, -40], "translation": [0, 2, 2], "scale": [1.7, 1.7, 0.85]}}}""";
        assertFirstPersonMatchesJava(spear);
        assertThirdPersonMatchesJava(spear);
    }

    /**
     * Pins the third-person rig used above to Mojang's own data. Java's shield is ShieldModel
     * (plate and handle) under its special renderer's scale(1, -1, -1) with shield.json's
     * thirdperson_righthand. Bedrock's is geometry.shield under animation.shield.wield_third_person.
     * Mojang tuned the two by hand, so they agree to about a pixel.
     */
    @Test
    void mojangShieldPairAgreesOnTheThirdPersonRig() {
        double[] javaTranslation = {10, 6, -4};
        double[] javaRotation = {0, 90, 0};
        double[] javaScale = {1, 1, 1};
        double[] pivot = {1, 15.5, 3};
        double[] position = {-0.4, 9, 9.3};
        double[] rotation = {-90, 0, 90};
        double[] scale = {1, -1, -1};

        double[][][] javaBoxes = {{{-6, -11, -2}, {6, 11, -1}}, {{-1, -3, -1}, {1, 3, 5}}};
        for (double[] armPose : ARM_POSES) {
            for (double[][] box : javaBoxes) {
                for (double[] corner : corners(box[0], box[1])) {
                    double[] javaVertex = {corner[0], -corner[1], -corner[2]};
                    // Mojang ports ShieldModel to geometry.shield as (x + 1, 28 - y, z + 1).
                    double[] geometry = {corner[0] + 1, 28 - corner[1], corner[2] + 1};
                    double[] java = javaThirdPerson(javaVertex, javaTranslation, javaRotation, javaScale, armPose);
                    double[] bedrock = bedrockThirdPerson(geometry, pivot, position, rotation, scale, armPose);
                    assertTrue(distance(java, bedrock) < 1.2,
                            "shield corner " + format(corner) + " is " + distance(java, bedrock) + " px apart");
                }
            }
        }
    }

    private static String wand(String scale) {
        return """
                {"elements": [
                   {"from": [7, 12.25, -2.75], "to": [9, 14.25, 22]},
                   {"from": [6.2, 12.25, 22], "to": [9.75, 15.75, 25.34]}],
                 "display": {
                   "firstperson_righthand":
                     {"rotation": [-90, 0, 0], "translation": [1.25, -1, 6], "scale": %1$s},
                   "thirdperson_righthand":
                     {"rotation": [-90, 0, 0], "translation": [0, -1, 4.75], "scale": %1$s}}}""".formatted(scale);
    }

    private record Converted(JsonObject bone, JsonObject animations, JsonObject display) {
        JsonObject pose(String animation) {
            return animations.getAsJsonObject("animation.rspm.test." + animation)
                    .getAsJsonObject("bones").getAsJsonObject("bone");
        }

        JsonObject displaySlot(String slot) {
            return display.has(slot) ? display.getAsJsonObject(slot) : new JsonObject();
        }

        List<double[]> geometryCorners() {
            List<double[]> corners = new ArrayList<>();
            for (JsonElement cubeElement : bone.getAsJsonArray("cubes")) {
                JsonObject cube = cubeElement.getAsJsonObject();
                double[] origin = vector(cube.get("origin"), 0);
                corners.addAll(corners(origin, add(origin, vector(cube.get("size"), 0))));
            }
            return corners;
        }
    }

    private Converted convert(String modelJson) throws Exception {
        JsonObject model = JsonParser.parseString(modelJson).getAsJsonObject();
        File pack = Files.createTempDirectory(directory, "pack").toFile();
        assertNotNull(FmmGeometryConverter.convertWithIdentifier("geometry.rspm.test", "test", model,
                Map.of(), 16, 16, pack));
        assertNotNull(FmmAttachableGenerator.prepareAnimations("rspm.test", "test", model, pack));

        JsonObject bone = JsonParser.parseString(Files.readString(pack.toPath().resolve("models/entity/test.geo.json")))
                .getAsJsonObject().getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject()
                .getAsJsonArray("bones").get(0).getAsJsonObject();
        JsonObject animations = JsonParser.parseString(
                        Files.readString(pack.toPath().resolve("animations/test.animation.json")))
                .getAsJsonObject().getAsJsonObject("animations");
        JsonObject display = model.has("display") ? model.getAsJsonObject("display") : new JsonObject();
        return new Converted(bone, animations, display);
    }

    /** @return the emitted first-person bone position, for callers comparing poses */
    private double[] assertFirstPersonMatchesJava(String modelJson) throws Exception {
        Converted converted = convert(modelJson);
        JsonObject pose = converted.pose("hold_first_person");
        double[] pivot = vector(converted.bone().get("pivot"), 0);
        double[] position = vector(pose.get("position"), 0);
        double[] rotation = vector(pose.get("rotation"), 0);
        double[] scale = vector(pose.get("scale"), 1);

        JsonObject display = converted.displaySlot("firstperson_righthand");
        double[] javaTranslation = vector(display.get("translation"), 0);
        double[] javaRotation = vector(display.get("rotation"), 0);
        double[] javaScale = vector(display.get("scale"), 1);

        for (double[] geometry : converted.geometryCorners()) {
            assertArrayEquals(javaView(javaVertex(geometry), javaTranslation, javaRotation, javaScale),
                    bedrockView(geometry, pivot, position, rotation, scale), 1e-4,
                    "first-person corner " + format(geometry));
        }
        return position;
    }

    private void assertThirdPersonMatchesJava(String modelJson) throws Exception {
        Converted converted = convert(modelJson);
        JsonObject pose = converted.pose("hold_third_person");
        double[] pivot = vector(converted.bone().get("pivot"), 0);
        double[] position = vector(pose.get("position"), 0);
        double[] rotation = vector(pose.get("rotation"), 0);
        double[] scale = vector(pose.get("scale"), 1);

        JsonObject display = converted.displaySlot("thirdperson_righthand");
        double[] javaTranslation = vector(display.get("translation"), 0);
        double[] javaRotation = vector(display.get("rotation"), 0);
        double[] javaScale = vector(display.get("scale"), 1);

        for (double[] armPose : ARM_POSES) {
            for (double[] geometry : converted.geometryCorners()) {
                assertArrayEquals(
                        javaThirdPerson(javaVertex(geometry), javaTranslation, javaRotation, javaScale, armPose),
                        bedrockThirdPerson(geometry, pivot, position, rotation, scale, armPose), 1e-3,
                        "third-person corner " + format(geometry) + " with arm " + format(armPose));
            }
        }
    }

    /** FmmGeometryConverter centres X and Z on 8 and mirrors X. */
    private static double[] javaVertex(double[] geometry) {
        return new double[]{8 - geometry[0], geometry[1], geometry[2] + 8};
    }

    private static double[] javaView(double[] vertex, double[] translation, double[] rotation, double[] scale) {
        double[] centred = {vertex[0] / 16 - 0.5, vertex[1] / 16 - 0.5, vertex[2] / 16 - 0.5};
        double[] turned = apply(multiply(rotX(rotation[0]), multiply(rotY(rotation[1]), rotZ(rotation[2]))),
                multiplyEach(scale, centred));
        return add(add(JAVA_VIEW_HAND, scaled(translation, 1 / 16.0)), turned);
    }

    /**
     * Java's third-person right hand in pixels: translateToHand (arm offset, then the part's
     * Rz·Ry·Rx), ItemInHandLayer's Rx(-90)·Ry(180) and hand offset, then the display transform.
     * LivingEntityRenderer's scale(-1, -1, 1) and 1.5-block lift take the model point into Bedrock's
     * rendered model frame.
     */
    private static double[] javaThirdPerson(double[] vertex, double[] translation, double[] rotation,
                                            double[] scale, double[] armPose) {
        double[] centred = {vertex[0] - 8, vertex[1] - 8, vertex[2] - 8};
        double[] item = add(add(JAVA_HAND_OFFSET, translation),
                apply(multiply(rotX(rotation[0]), multiply(rotY(rotation[1]), rotZ(rotation[2]))),
                        multiplyEach(scale, centred)));
        double[] arm = apply(multiply(rotX(-90), rotY(180)), item);
        double[] model = add(JAVA_ARM_OFFSET,
                apply(multiply(rotZ(armPose[2]), multiply(rotY(armPose[1]), rotX(armPose[0]))), arm));
        return new double[]{-model[0], 24 - model[1], model[2]};
    }

    /** Bedrock files mirror X; everything below runs in the rendered frame. */
    private static double[] bedrockView(double[] geometry, double[] pivot, double[] position,
                                        double[] rotation, double[] scale) {
        double[] point = add(bone(geometry, pivot, position, rotation, scale),
                subtract(add(mirror(ITEM_PIVOT), mirror(FIRST_PERSON_ITEM_POSITION)), BINDING_ORIGIN));
        double[] armPivot = mirror(ARM_PIVOT);
        point = add(add(apply(bedrockRotation(FIRST_PERSON_ARM_ROTATION), subtract(point, armPivot)), armPivot),
                mirror(FIRST_PERSON_ARM_POSITION));
        return scaled(apply(rotY(180), subtract(point, CAMERA)), 1 / 16.0);
    }

    /** The third-person rig leaves rightItem where the geometry puts it; only the arm moves. */
    private static double[] bedrockThirdPerson(double[] geometry, double[] pivot, double[] position,
                                               double[] rotation, double[] scale, double[] armPose) {
        double[] point = add(bone(geometry, pivot, position, rotation, scale),
                subtract(mirror(ITEM_PIVOT), BINDING_ORIGIN));
        double[] armPivot = mirror(ARM_PIVOT);
        return add(apply(bedrockRotation(armPose), subtract(point, armPivot)), armPivot);
    }

    private static double[] bone(double[] geometry, double[] pivot, double[] position,
                                 double[] rotation, double[] scale) {
        double[] p = mirror(pivot);
        return add(add(mirror(position), p),
                apply(bedrockRotation(rotation), multiplyEach(scale, subtract(mirror(geometry), p))));
    }

    private static double[][] bedrockRotation(double[] r) {
        return multiply(rotZ(r[2]), multiply(rotY(-r[1]), rotX(-r[0])));
    }

    private static List<double[]> corners(double[] from, double[] to) {
        List<double[]> corners = new ArrayList<>();
        for (int corner = 0; corner < 8; corner++) {
            corners.add(new double[]{
                    (corner & 1) == 0 ? from[0] : to[0],
                    (corner & 2) == 0 ? from[1] : to[1],
                    (corner & 4) == 0 ? from[2] : to[2]});
        }
        return corners;
    }

    private static double[] vector(JsonElement element, double fallback) {
        if (element == null) return new double[]{fallback, fallback, fallback};
        JsonArray array = element.getAsJsonArray();
        return new double[]{array.get(0).getAsDouble(), array.get(1).getAsDouble(), array.get(2).getAsDouble()};
    }

    private static String format(double[] v) {
        return "(" + v[0] + ", " + v[1] + ", " + v[2] + ")";
    }

    private static double distance(double[] a, double[] b) {
        double[] d = subtract(a, b);
        return Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
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
