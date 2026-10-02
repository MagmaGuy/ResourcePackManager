package com.magmaguy.resourcepackmanager.bedrock.converter;

/**
 * Maps a Java item model's {@code display.firstperson_righthand} transform onto the
 * first-person animation of the Bedrock attachable bone, so the held model lands where Java
 * draws it: same screen position, orientation and size.
 *
 * <h2>Java</h2>
 * ItemInHandRenderer draws the right-hand item (fully equipped, not swinging) in view space
 * (blocks; X right, Y up, camera looking down -Z) as
 * <pre>  view = HAND + t / 16 + Rx(rx)·Ry(ry)·Rz(rz) · S · (v / 16 - 0.5)</pre>
 * with HAND = (0.56, -0.52, -0.72) from {@code applyItemArmTransform} and v a model vertex in
 * pixels. Rotation and scale act about the block centre (8, 8, 8); the translation is not
 * scaled. The hand projection uses a fixed 70° field of view.
 *
 * <h2>Bedrock</h2>
 * The attachable bone is bound to the player's {@code rightItem} bone. The vanilla resource
 * pack (1.21.130 and later) poses the first-person rig as follows:
 * <ul>
 *   <li>{@code animation.player.first_person.empty_hand} moves rightArm (pivot (-5, 22, 0)) by
 *       (13.5, -10, 12), rotates it (95, -45, 115), and moves rightItem (pivot (-6, 15, 1)) by
 *       (0, armPivotY - itemPivotY - 7, -itemPivotZ) = (0, 0, -1).</li>
 *   <li>The binding puts attachable geometry point (0, 24, 0) on the rightItem pivot. The
 *       vanilla spear's hold animation lifts its model by the same 24.</li>
 *   <li>The camera sits at (0, 27.41, 0) in player model space, looks along +Z, and has
 *       Java's field of view. These are the values of Blockbench's first-person attachable
 *       preview.</li>
 * </ul>
 * Mojang poses the vanilla shield separately in each edition. Under this rig the Java and
 * Bedrock shields land within about 5° of each other on screen; without the binding offset
 * they miss by tens of degrees.
 *
 * <p>Bedrock files mirror X relative to the rendered model. This class works in the rendered
 * frame (file X negated). There, a bone with file rotation (x, y, z) rotates by
 * Rz(z)·Ry(-y)·Rx(-x) about its pivot, scale applies before the rotation, and the animation
 * position (X negated) is added afterwards.
 *
 * <p>The rig is a fixed rigid map from attachable geometry to Java view space. Inverting it and
 * applying Java's transform gives the bone's rotation as one matrix, which keeps Java's XYZ
 * order intact. It also gives the scale, and the position that puts Java's block centre where
 * Java draws it, whatever the bone's pivot.
 */
final class FirstPersonTransform {

    /** Bedrock bone animation values in file conventions (pixels, degrees). */
    record BoneTransform(double[] position, double[] rotation, double[] scale) {}

    private static final double[] JAVA_HAND = {0.56, -0.52, -0.72};
    private static final double[] RIGHT_ARM_PIVOT = {-5, 22, 0};
    private static final double[] RIGHT_ARM_POSITION = {13.5, -10, 12};
    private static final double[] RIGHT_ARM_ROTATION = {95, -45, 115};
    private static final double[] RIGHT_ITEM_PIVOT = {-6, 15, 1};
    private static final double[] RIGHT_ITEM_POSITION = {0, 0, -1};
    private static final double[] BINDING_ORIGIN = {0, 24, 0};
    private static final double[] CAMERA = {0, 27.41, 0};
    /** Java's block centre (8, 8, 8) in geometry coordinates; FmmGeometryConverter centres X and Z on 8. */
    private static final double[] BLOCK_CENTRE = {0, 8, 0};

    /** Rotation from Java view axes into rendered geometry axes. */
    private static final double[][] VIEW_TO_GEOMETRY;
    /** Java's hand point (view origin of every display transform) in rendered geometry pixels. */
    private static final double[] HAND_IN_GEOMETRY;

    static {
        double[][] cameraTurn = rotationY(180); // camera looks along +Z, Java's along -Z
        double[][] arm = boneRotation(RIGHT_ARM_ROTATION);
        double[][] armInverse = transpose(arm);
        VIEW_TO_GEOMETRY = multiply(armInverse, cameraTurn);

        // Walk Java's hand point back through camera, arm and binding.
        double[] point = add(apply(cameraTurn, scale(JAVA_HAND, 16)), rendered(CAMERA));
        point = add(apply(armInverse, subtract(point, add(rendered(RIGHT_ARM_PIVOT), rendered(RIGHT_ARM_POSITION)))),
                rendered(RIGHT_ARM_PIVOT));
        HAND_IN_GEOMETRY = subtract(point,
                subtract(add(rendered(RIGHT_ITEM_PIVOT), rendered(RIGHT_ITEM_POSITION)), BINDING_ORIGIN));
    }

    private FirstPersonTransform() {}

    /**
     * @param display   the Java first-person display, or null for Java's identity transform
     * @param bonePivot the geometry bone pivot in Bedrock file coordinates
     */
    static BoneTransform fromJava(FmmAnimationGenerator.JavaDisplay display, double[] bonePivot) {
        double[] translation = display == null ? new double[]{0, 0, 0} : display.translation;
        double[] rotation = display == null ? new double[]{0, 0, 0} : display.rotation;
        double[] scale = display == null ? new double[]{1, 1, 1} : display.scale;

        double[][] boneRotation = multiply(VIEW_TO_GEOMETRY,
                multiply(rotationX(rotation[0]), multiply(rotationY(rotation[1]), rotationZ(rotation[2]))));
        double[] pivot = rendered(bonePivot);
        double[] pivotFromCentre = subtract(pivot, BLOCK_CENTRE);
        double[] scaledPivotOffset = {
                scale[0] * pivotFromCentre[0], scale[1] * pivotFromCentre[1], scale[2] * pivotFromCentre[2]};
        double[] position = add(add(HAND_IN_GEOMETRY, apply(VIEW_TO_GEOMETRY, translation)),
                subtract(apply(boneRotation, scaledPivotOffset), pivot));

        return new BoneTransform(round(rendered(position)), round(fileRotation(boneRotation)), scale.clone());
    }

    /** Rendered-frame rotation of a bone whose file rotation is {@code r}. */
    static double[][] boneRotation(double[] r) {
        return multiply(rotationZ(r[2]), multiply(rotationY(-r[1]), rotationX(-r[0])));
    }

    /** Inverse of {@link #boneRotation}: decomposes m = Rz(c)·Ry(b)·Rx(a) and returns file angles (-a, -b, c). */
    private static double[] fileRotation(double[][] m) {
        double sinB = Math.max(-1, Math.min(1, -m[2][0]));
        double a, b, c;
        if (Math.abs(sinB) < 0.9999995) {
            b = Math.asin(sinB);
            a = Math.atan2(m[2][1], m[2][2]);
            c = Math.atan2(m[1][0], m[0][0]);
        } else {
            b = Math.copySign(Math.PI / 2, sinB);
            a = Math.atan2(-m[1][2], m[1][1]);
            c = 0;
        }
        return new double[]{-Math.toDegrees(a), -Math.toDegrees(b), Math.toDegrees(c)};
    }

    /** File coordinates to the rendered frame and back: both negate X. */
    private static double[] rendered(double[] v) {
        return new double[]{-v[0], v[1], v[2]};
    }

    static double[][] rotationX(double degrees) {
        double c = Math.cos(Math.toRadians(degrees)), s = Math.sin(Math.toRadians(degrees));
        return new double[][]{{1, 0, 0}, {0, c, -s}, {0, s, c}};
    }

    static double[][] rotationY(double degrees) {
        double c = Math.cos(Math.toRadians(degrees)), s = Math.sin(Math.toRadians(degrees));
        return new double[][]{{c, 0, s}, {0, 1, 0}, {-s, 0, c}};
    }

    static double[][] rotationZ(double degrees) {
        double c = Math.cos(Math.toRadians(degrees)), s = Math.sin(Math.toRadians(degrees));
        return new double[][]{{c, -s, 0}, {s, c, 0}, {0, 0, 1}};
    }

    static double[][] multiply(double[][] a, double[][] b) {
        double[][] out = new double[3][3];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                out[i][j] = a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j];
        return out;
    }

    static double[] apply(double[][] m, double[] v) {
        return new double[]{
                m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
                m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
                m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]};
    }

    private static double[][] transpose(double[][] m) {
        return new double[][]{{m[0][0], m[1][0], m[2][0]}, {m[0][1], m[1][1], m[2][1]}, {m[0][2], m[1][2], m[2][2]}};
    }

    private static double[] add(double[] a, double[] b) {
        return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    private static double[] subtract(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double[] scale(double[] v, double factor) {
        return new double[]{v[0] * factor, v[1] * factor, v[2] * factor};
    }

    private static double[] round(double[] v) {
        return new double[]{round(v[0]), round(v[1]), round(v[2])};
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
