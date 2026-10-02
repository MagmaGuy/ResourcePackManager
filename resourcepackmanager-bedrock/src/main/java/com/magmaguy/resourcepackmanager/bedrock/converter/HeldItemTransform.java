package com.magmaguy.resourcepackmanager.bedrock.converter;

/**
 * Maps a Java item model's {@code display.firstperson_righthand} and
 * {@code display.thirdperson_righthand} transforms onto the held-item animations of the Bedrock
 * attachable bone, so the held model lands where Java draws it: same position, orientation and
 * size.
 *
 * <p>Java draws a model vertex v (pixels) in its display frame as
 * <pre>  hand + t + Rx(rx)·Ry(ry)·Rz(rz) · S · (v - 8)</pre>
 * Rotation and scale act about the block centre (8, 8, 8); the translation is not scaled. Each
 * perspective has a rig: a fixed rigid map from that display frame into the attachable's
 * geometry. Applying Java's transform through the rig gives the bone's rotation as one matrix,
 * which keeps Java's XYZ order intact. It also gives the scale, and the position that puts Java's
 * block centre where Java draws it, whatever the bone's pivot.
 *
 * <h2>Bedrock conventions</h2>
 * Bedrock files mirror X relative to the rendered model. This class works in the rendered frame
 * (file X negated). There, a bone with file rotation (x, y, z) rotates by Rz(z)·Ry(-y)·Rx(-x)
 * about its pivot, scale applies before the rotation, and the animation position (X negated) is
 * added afterwards. The attachable bone is bound to the player's {@code rightItem} bone
 * (pivot (-6, 15, 1), a child of rightArm, pivot (-5, 22, 0)). The binding puts attachable
 * geometry point (0, 24, 0) on the rightItem pivot; the vanilla spear's hold animations lift
 * its model by the same 24.
 *
 * <h2>First person</h2>
 * ItemInHandRenderer draws the right-hand item (fully equipped, not swinging) in view space
 * (blocks; X right, Y up, camera looking down -Z) with hand = (0.56, -0.52, -0.72) from
 * {@code applyItemArmTransform}. The hand projection uses a fixed 70° field of view. The vanilla
 * Bedrock resource pack (1.21.130 and later) poses the first-person rig as follows:
 * <ul>
 *   <li>{@code animation.player.first_person.empty_hand} moves rightArm by (13.5, -10, 12),
 *       rotates it (95, -45, 115), and moves rightItem by
 *       (0, armPivotY - itemPivotY - 7, -itemPivotZ) = (0, 0, -1).</li>
 *   <li>The camera sits at (0, 27.41, 0) in player model space, looks along +Z, and has Java's
 *       field of view. These are the values of Blockbench's first-person attachable preview.</li>
 * </ul>
 * Mojang poses the vanilla shield separately in each edition. Under this rig the Java and
 * Bedrock shields land within about 5° of each other on screen; without the binding offset they
 * miss by tens of degrees.
 *
 * <h2>Third person</h2>
 * ItemInHandLayer moves to the hand with {@code translateToHand} (the right arm part: offset
 * (-5, 2, 0), then its rotation Rz·Ry·Rx), rotates by Rx(-90)·Ry(180), and translates by
 * (1, 2, -10) pixels before the display transform. LivingEntityRenderer flips the model with
 * scale(-1, -1, 1) and lifts it 1.5 blocks, so a Java model point (x, y, z) sits at
 * (-x, 24 - y, z) in Bedrock's rendered model frame. That puts the Java arm offset on the
 * Bedrock rightArm pivot, and a Java part rotation (x, y, z) equals a Bedrock bone rotation with
 * the same angles. Both editions pose the holding arm alike: Java's ITEM pose sets
 * xRot·0.5 - 18°, {@code animation.player.holding} sets -this·0.5 - 18, and the idle bob matches.
 * Bedrock leaves rightItem unanimated in third person for ordinary items. The rig is therefore
 * fixed in the arm's frame, and the item follows the arm in both editions, whatever the arm
 * does.
 *
 * <p>Mojang poses the shield separately here too. Under this rig every corner of Java's
 * third-person shield lands within 1.2 pixels of Bedrock's
 * {@code animation.shield.wield_third_person}, with the same orientation. Without the binding
 * offset it misses by 23.5 pixels.
 *
 * <p>The slim (Alex) arm moves Java's hand 0.5 pixels inward, and Bedrock's slim rightItem stays
 * where it is; the rigs use the wide arm.
 */
final class HeldItemTransform {

    /** Bedrock bone animation values in file conventions (pixels, degrees). */
    record BoneTransform(double[] position, double[] rotation, double[] scale) {}

    /** Rigid map from Java's display frame into rendered attachable geometry. */
    private record Rig(double[][] javaToGeometry, double[] handInGeometry) {}

    private static final double[] RIGHT_ARM_PIVOT = {-5, 22, 0};
    private static final double[] RIGHT_ITEM_PIVOT = {-6, 15, 1};
    private static final double[] BINDING_ORIGIN = {0, 24, 0};
    /** Java's block centre (8, 8, 8) in geometry coordinates; FmmGeometryConverter centres X and Z on 8. */
    private static final double[] BLOCK_CENTRE = {0, 8, 0};

    // First person
    private static final double[] JAVA_VIEW_HAND = {0.56, -0.52, -0.72};
    private static final double[] FIRST_PERSON_ARM_POSITION = {13.5, -10, 12};
    private static final double[] FIRST_PERSON_ARM_ROTATION = {95, -45, 115};
    private static final double[] FIRST_PERSON_ITEM_POSITION = {0, 0, -1};
    private static final double[] FIRST_PERSON_CAMERA = {0, 27.41, 0};

    // Third person: ItemInHandLayer's offset from the arm, in pixels
    private static final double[] JAVA_ITEM_IN_HAND_OFFSET = {1, 2, -10};
    /** Java model frame to Bedrock's rendered model frame, without the 24-pixel lift. */
    private static final double[][] JAVA_MODEL_TO_RENDERED = {{-1, 0, 0}, {0, -1, 0}, {0, 0, 1}};

    private static final Rig FIRST_PERSON = firstPersonRig();
    private static final Rig THIRD_PERSON = thirdPersonRig();

    private HeldItemTransform() {}

    /**
     * @param display   the Java {@code firstperson_righthand} display, or null for Java's identity transform
     * @param bonePivot the geometry bone pivot in Bedrock file coordinates
     */
    static BoneTransform firstPerson(FmmAnimationGenerator.JavaDisplay display, double[] bonePivot) {
        return solve(FIRST_PERSON, display, bonePivot);
    }

    /**
     * @param display   the Java {@code thirdperson_righthand} display, or null for Java's identity transform
     * @param bonePivot the geometry bone pivot in Bedrock file coordinates
     */
    static BoneTransform thirdPerson(FmmAnimationGenerator.JavaDisplay display, double[] bonePivot) {
        return solve(THIRD_PERSON, display, bonePivot);
    }

    private static Rig firstPersonRig() {
        double[][] cameraTurn = rotationY(180); // camera looks along +Z, Java's along -Z
        double[][] armInverse = transpose(boneRotation(FIRST_PERSON_ARM_ROTATION));

        // Walk Java's hand point back through camera, arm and binding.
        double[] point = add(apply(cameraTurn, scale(JAVA_VIEW_HAND, 16)), rendered(FIRST_PERSON_CAMERA));
        point = add(apply(armInverse,
                        subtract(point, add(rendered(RIGHT_ARM_PIVOT), rendered(FIRST_PERSON_ARM_POSITION)))),
                rendered(RIGHT_ARM_PIVOT));
        double[] hand = subtract(point,
                subtract(add(rendered(RIGHT_ITEM_PIVOT), rendered(FIRST_PERSON_ITEM_POSITION)), BINDING_ORIGIN));
        return new Rig(multiply(armInverse, cameraTurn), hand);
    }

    /**
     * Both editions hang the item off the same arm, so the rig is solved in the arm's frame: the
     * Java offset from the arm, against the binding offset from the rightArm pivot.
     */
    private static Rig thirdPersonRig() {
        double[][] javaToGeometry = multiply(JAVA_MODEL_TO_RENDERED, multiply(rotationX(-90), rotationY(180)));
        double[] bindingOffset = subtract(rendered(RIGHT_ITEM_PIVOT), BINDING_ORIGIN);
        double[] hand = add(subtract(rendered(RIGHT_ARM_PIVOT), bindingOffset),
                apply(javaToGeometry, JAVA_ITEM_IN_HAND_OFFSET));
        return new Rig(javaToGeometry, hand);
    }

    private static BoneTransform solve(Rig rig, FmmAnimationGenerator.JavaDisplay display, double[] bonePivot) {
        double[] translation = display == null ? new double[]{0, 0, 0} : display.translation;
        double[] rotation = display == null ? new double[]{0, 0, 0} : display.rotation;
        double[] scale = display == null ? new double[]{1, 1, 1} : display.scale;

        double[][] boneRotation = multiply(rig.javaToGeometry(),
                multiply(rotationX(rotation[0]), multiply(rotationY(rotation[1]), rotationZ(rotation[2]))));
        double[] pivot = rendered(bonePivot);
        double[] pivotFromCentre = subtract(pivot, BLOCK_CENTRE);
        double[] scaledPivotOffset = {
                scale[0] * pivotFromCentre[0], scale[1] * pivotFromCentre[1], scale[2] * pivotFromCentre[2]};
        double[] position = add(add(rig.handInGeometry(), apply(rig.javaToGeometry(), translation)),
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
