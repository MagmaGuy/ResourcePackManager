package com.magmaguy.resourcepackmanager.bedrock.converter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.magmaguy.resourcepackmanager.bedrock.BedrockLog;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java software rasterizer that generates 64x64 inventory icons from Java
 * 3D item models. Replaces the legacy "UV-atlas as icon" placeholder in the
 * generic Bedrock pipeline.
 *
 * <p>Algorithm summary:
 * <ol>
 *   <li>Parse {@code elements[]} from the merged Java model JSON.</li>
 *   <li>Read {@code display.gui} (rotation/translation/scale), defaulting to
 *       Mojang's GUI camera ({@code rotation = [30, 225, 0]}, no translation,
 *       {@code scale = [1, 1, 1]}). The vanilla default scale {@code 0.625}
 *       only applies to {@code item/generated}-rooted (flat) models; cuboid
 *       models authored without an explicit {@code display.gui} look correct
 *       at scale 1.</li>
 *   <li>For each element, build the 8 cube corners in Java's 16-unit space
 *       centred at (8, 8, 8). Apply the per-element rotation (around its own
 *       origin), then the {@code display.gui} pivot rotation around (8,8,8),
 *       then {@code display.gui} scale and translation.</li>
 *   <li>For each of the 6 faces, back-face cull against -Z and compute the
 *       2D destination quad on the 64x64 canvas (orthographic, 4 px per
 *       model unit).</li>
 *   <li>Paint visible faces back-to-front via painter's algorithm using a
 *       Java2D {@link AffineTransform} that maps the source-texture UV rect
 *       to the destination parallelogram. Nearest-neighbour interpolation
 *       keeps the pixel-art aesthetic.</li>
 * </ol>
 *
 * <p>UV defaults (when {@code faces.<name>.uv} is absent), per-face UV
 * rotation, and the up/down-face flip convention all follow vanilla
 * {@code FaceBakery} semantics &mdash; the same rules already implemented in
 * {@link FmmGeometryConverter#defaultFaceUV} are mirrored here (the helper
 * itself is private, but the algorithm is straightforward enough to inline).
 */
public final class IconRenderer {

    /** Output canvas size in pixels. */
    private static final int ICON_SIZE = 64;
    /** Pixels per Java-model unit. Mojang's GUI fits a 16-unit cube in the slot. */
    private static final double PIXELS_PER_UNIT = ICON_SIZE / 16.0;
    /** World-space centre of the 16-unit Java model cube. */
    private static final double CENTRE = 8.0;

    /** Default Mojang GUI rotation when a model omits {@code display.gui}. */
    private static final double[] DEFAULT_GUI_ROTATION = {30, 225, 0};
    private static final double[] DEFAULT_GUI_TRANSLATION = {0, 0, 0};
    /**
     * Default GUI scale for cuboid item models without an explicit
     * {@code display.gui}. Mojang's {@code item/generated}-rooted parent ships
     * {@code 0.625} but that's intended for the flat layer-based renderer; for
     * the 3D path we keep 1.0 so the full 16-unit cube fills the slot.
     */
    private static final double[] DEFAULT_GUI_SCALE = {1.0, 1.0, 1.0};

    private IconRenderer() {}

    /**
     * Renders a 64x64 inventory icon for a Java 3D item model and writes it to
     * {@code outputPng}. Returns {@code true} on success; {@code false} if the
     * model can't be rendered (caller should fall back to
     * {@link #writeMissingPlaceholder(File)}).
     *
     * @param resolvedModel   parsed merged JSON of the model (parent chain already resolved)
     * @param mergedJavaPack  root of the merged Java pack (for texture resolution)
     * @param outputPng       destination PNG file
     */
    public static boolean renderIcon(JsonObject resolvedModel, File mergedJavaPack, File outputPng) {
        try {
            if (resolvedModel == null) return false;
            if (!resolvedModel.has("elements") || !resolvedModel.get("elements").isJsonArray()) {
                return false;
            }
            JsonArray elements = resolvedModel.getAsJsonArray("elements");
            if (elements.isEmpty()) return false;

            // --- texture name -> resolved texture reference (flatten "#layer0" -> "elitemobs:items/bronzesword") ---
            Map<String, String> textureRefs = readTextureRefs(resolvedModel);

            // --- display.gui transform (translation/rotation/scale in pixel units) ---
            double[] guiRotation = DEFAULT_GUI_ROTATION;
            double[] guiTranslation = DEFAULT_GUI_TRANSLATION;
            double[] guiScale = DEFAULT_GUI_SCALE;
            if (resolvedModel.has("display") && resolvedModel.get("display").isJsonObject()) {
                JsonObject display = resolvedModel.getAsJsonObject("display");
                if (display.has("gui") && display.get("gui").isJsonObject()) {
                    JsonObject gui = display.getAsJsonObject("gui");
                    guiRotation = readVec3(gui, "rotation", DEFAULT_GUI_ROTATION);
                    guiTranslation = readVec3(gui, "translation", DEFAULT_GUI_TRANSLATION);
                    guiScale = readVec3(gui, "scale", DEFAULT_GUI_SCALE);
                }
            }

            // --- collect all visible faces ---
            List<FaceDraw> draws = new ArrayList<>();
            Map<String, BufferedImage> textureCache = new HashMap<>();

            for (JsonElement el : elements) {
                if (!el.isJsonObject()) continue;
                JsonObject element = el.getAsJsonObject();
                if (!element.has("from") || !element.has("to")) continue;
                JsonArray from = element.getAsJsonArray("from");
                JsonArray to = element.getAsJsonArray("to");
                if (from.size() < 3 || to.size() < 3) continue;

                double fx = from.get(0).getAsDouble();
                double fy = from.get(1).getAsDouble();
                double fz = from.get(2).getAsDouble();
                double tx = to.get(0).getAsDouble();
                double ty = to.get(1).getAsDouble();
                double tz = to.get(2).getAsDouble();

                // Per-element rotation (axis + angle around its own origin), if any.
                double[] elementRotAxis = {0, 0, 0};
                double elementRotAngle = 0;
                double[] elementRotOrigin = {CENTRE, CENTRE, CENTRE};
                if (element.has("rotation") && element.get("rotation").isJsonObject()) {
                    JsonObject rot = element.getAsJsonObject("rotation");
                    elementRotAngle = rot.has("angle") ? rot.get("angle").getAsDouble() : 0;
                    String axis = rot.has("axis") ? rot.get("axis").getAsString() : "y";
                    switch (axis) {
                        case "x" -> elementRotAxis = new double[]{1, 0, 0};
                        case "y" -> elementRotAxis = new double[]{0, 1, 0};
                        case "z" -> elementRotAxis = new double[]{0, 0, 1};
                    }
                    if (rot.has("origin") && rot.get("origin").isJsonArray()) {
                        JsonArray o = rot.getAsJsonArray("origin");
                        if (o.size() >= 3) {
                            elementRotOrigin = new double[]{
                                    o.get(0).getAsDouble(),
                                    o.get(1).getAsDouble(),
                                    o.get(2).getAsDouble()
                            };
                        }
                    }
                }

                if (!element.has("faces") || !element.get("faces").isJsonObject()) continue;
                JsonObject faces = element.getAsJsonObject("faces");

                for (String faceName : new String[]{"north", "east", "south", "west", "up", "down"}) {
                    if (!faces.has(faceName) || !faces.get(faceName).isJsonObject()) continue;
                    JsonObject face = faces.getAsJsonObject(faceName);

                    // 4 face corners in face-canonical order (matches FaceBakery winding).
                    double[][] cornersWorld = faceCorners(faceName, fx, fy, fz, tx, ty, tz);
                    double[][] transformed = new double[4][3];
                    for (int i = 0; i < 4; i++) {
                        double[] v = cornersWorld[i];
                        if (elementRotAngle != 0) {
                            v = rotateAroundAxis(v, elementRotAxis, elementRotAngle, elementRotOrigin);
                        }
                        // display.gui transform — order is M = T · R · S (Three.js /
                        // Blockbench convention; Blockbench is Mojang's reference Java-model
                        // preview tool, using Three.js's Matrix4.compose). So: scale first,
                        // then rotation, then translation. The rotation pivot is the model
                        // origin (0,0,0), NOT the cube centre — Blockbench's display_mode.js
                        // updateDisplayBase sets transforms on the model Group at its origin.
                        // Euler order is intrinsic XYZ (Three.js default) — matrix Rx·Ry·Rz
                        // applied to a point means sequential rotateZ → rotateY → rotateX.
                        v = new double[]{v[0] * guiScale[0], v[1] * guiScale[1], v[2] * guiScale[2]};
                        v = rotateZ(v, guiRotation[2]);
                        v = rotateY(v, guiRotation[1]);
                        v = rotateX(v, guiRotation[0]);
                        v = new double[]{
                                v[0] + guiTranslation[0],
                                v[1] + guiTranslation[1],
                                v[2] + guiTranslation[2]
                        };
                        transformed[i] = v;
                    }

                    // Back-face cull: signed area of the projected quad in image space
                    // (X-right, Y-down). With my corner winding (TL, TR, BR, BL when
                    // looking at the face from outside in model space), a face is
                    // FRONT-facing (visible) when its 2D screen winding is CLOCKWISE,
                    // i.e. signed area > 0 in (X-right, Y-down) coords. After all
                    // transforms, this directly reflects which faces the camera sees.
                    double s0x = transformed[0][0],          s0y = -transformed[0][1];
                    double s1x = transformed[1][0],          s1y = -transformed[1][1];
                    double s3x = transformed[3][0],          s3y = -transformed[3][1];
                    double crossZ = (s1x - s0x) * (s3y - s0y) - (s1y - s0y) * (s3x - s0x);
                    if (crossZ <= 0) continue;

                    // Resolve texture
                    String texKey = face.has("texture") ? face.get("texture").getAsString() : "#0";
                    String texRef = resolveTextureRef(texKey, textureRefs);
                    if (texRef == null) continue;
                    BufferedImage source = loadTexture(texRef, mergedJavaPack, textureCache);
                    if (source == null) continue;

                    // Resolve UV (explicit or default), scaled to source-texture pixel space.
                    double[] uv;
                    if (face.has("uv") && face.get("uv").isJsonArray()) {
                        JsonArray uvArr = face.getAsJsonArray("uv");
                        if (uvArr.size() < 4) continue;
                        uv = new double[]{
                                uvArr.get(0).getAsDouble(),
                                uvArr.get(1).getAsDouble(),
                                uvArr.get(2).getAsDouble(),
                                uvArr.get(3).getAsDouble()
                        };
                    } else {
                        uv = defaultFaceUV(faceName, fx, fy, fz, tx, ty, tz);
                    }

                    int faceRotation = 0;
                    if (face.has("rotation") && face.get("rotation").isJsonPrimitive()) {
                        int raw = face.get("rotation").getAsInt();
                        int normalized = ((raw % 360) + 360) % 360;
                        if (normalized % 90 == 0) faceRotation = normalized;
                    }

                    // Project to 2D screen-space pixel coordinates.
                    // Display transforms rotate around the model origin (0,0,0), so the
                    // 16-unit Java cube occupies model x,y ∈ [0, 16] before any user-supplied
                    // display.gui transform. We map that range to canvas pixels:
                    //   sx = vx * PIXELS_PER_UNIT
                    //   sy = ICON_SIZE - vy * PIXELS_PER_UNIT (flip Y; canvas Y is down-positive)
                    // With PIXELS_PER_UNIT = ICON_SIZE/16, the 16-unit cube maps exactly to
                    // the full canvas. display.gui.translation values shift within this space.
                    double[][] screen = new double[4][2];
                    double avgZ = 0;
                    for (int i = 0; i < 4; i++) {
                        double sx = transformed[i][0] * PIXELS_PER_UNIT;
                        double sy = ICON_SIZE - transformed[i][1] * PIXELS_PER_UNIT;
                        screen[i] = new double[]{sx, sy};
                        avgZ += transformed[i][2];
                    }
                    avgZ /= 4.0;

                    draws.add(new FaceDraw(faceName, source, uv, screen, avgZ, faceRotation));
                }
            }

            if (draws.isEmpty()) return false;

            // Painter's algorithm: deepest (most negative Z) first when camera looks down -Z.
            // With Mojang's GUI rotation, +Z after transform points toward camera, so we draw
            // smallest-Z first (farther from viewer).
            draws.sort(Comparator.comparingDouble(d -> d.avgZ));

            // --- auto-fit: compute the projected bounding box across all visible faces,
            // then scale+translate so the bbox fits inside the canvas with a small padding.
            // Without this, models with custom display.gui rotations / translations frequently
            // project outside the canvas (e.g. bronze_sword's [-3.25, -3.5, 0] translation +
            // a 0.65 scale doesn't end up centred in the 16-unit cube's natural projection).
            double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
            for (FaceDraw d : draws) {
                for (double[] pt : d.screen) {
                    if (pt[0] < minX) minX = pt[0];
                    if (pt[0] > maxX) maxX = pt[0];
                    if (pt[1] < minY) minY = pt[1];
                    if (pt[1] > maxY) maxY = pt[1];
                }
            }
            double bboxW = maxX - minX;
            double bboxH = maxY - minY;
            if (bboxW > 0 && bboxH > 0) {
                double padding = ICON_SIZE * 0.05; // ~5% margin
                double available = ICON_SIZE - 2 * padding;
                double fitScale = Math.min(available / bboxW, available / bboxH);
                double bboxCx = (minX + maxX) / 2.0;
                double bboxCy = (minY + maxY) / 2.0;
                double canvasCx = ICON_SIZE / 2.0;
                double canvasCy = ICON_SIZE / 2.0;
                for (FaceDraw d : draws) {
                    for (int i = 0; i < 4; i++) {
                        d.screen[i][0] = (d.screen[i][0] - bboxCx) * fitScale + canvasCx;
                        d.screen[i][1] = (d.screen[i][1] - bboxCy) * fitScale + canvasCy;
                    }
                }
            }

            // --- canvas + raster ---
            BufferedImage canvas = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = canvas.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_OFF);
                g.setComposite(AlphaComposite.SrcOver);

                for (FaceDraw d : draws) {
                    drawFace(g, d);
                }
            } finally {
                g.dispose();
            }

            // --- write PNG ---
            Files.createDirectories(outputPng.getParentFile().toPath());
            ImageIO.write(canvas, "PNG", outputPng);
            return true;
        } catch (Exception e) {
            BedrockLog.warn("[IconRenderer] Failed to render icon to " + outputPng.getPath()
                    + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Writes a 64x64 magenta/black missing-texture placeholder PNG (Mojang's
     * 8x8 cell checkerboard). Used as the fallback when {@link #renderIcon}
     * returns false.
     */
    public static boolean writeMissingPlaceholder(File outputPng) {
        try {
            BufferedImage canvas = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
            int magenta = 0xFFFF00FF;
            int black = 0xFF000000;
            int cellSize = ICON_SIZE / 8; // 8x8 grid -> 8px cells.
            for (int y = 0; y < ICON_SIZE; y++) {
                for (int x = 0; x < ICON_SIZE; x++) {
                    int cellX = x / cellSize;
                    int cellY = y / cellSize;
                    boolean magentaCell = ((cellX + cellY) & 1) == 0;
                    canvas.setRGB(x, y, magentaCell ? magenta : black);
                }
            }
            Files.createDirectories(outputPng.getParentFile().toPath());
            ImageIO.write(canvas, "PNG", outputPng);
            return true;
        } catch (IOException e) {
            BedrockLog.warn("[IconRenderer] Failed to write missing-texture placeholder to "
                    + outputPng.getPath() + ": " + e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------
    // Geometry helpers
    // ------------------------------------------------------------

    /**
     * Returns the 4 corners of a face of an axis-aligned cube
     * {@code [from..to]} in world-space (Java pixel units, 0..16).
     *
     * <p>Order follows FaceBakery's UV winding so corner 0 corresponds to the
     * UV minimum (u1, v1) and corner 2 to (u2, v2). This means:
     * <ul>
     *   <li>For side faces: corner 0 = top-left when looking at the face,
     *       corner 1 = top-right, corner 2 = bottom-right, corner 3 = bottom-left.</li>
     *   <li>For up/down: corner 0/1/2/3 trace the face counter-clockwise
     *       when looking at it from outside (matches the Mojang UV mapping
     *       which is then flipped via the up/down flip flag in
     *       {@link #drawFace}).</li>
     * </ul>
     */
    private static double[][] faceCorners(String face,
                                          double fx, double fy, double fz,
                                          double tx, double ty, double tz) {
        // Normalize so from <= to per-axis.
        double minX = Math.min(fx, tx), maxX = Math.max(fx, tx);
        double minY = Math.min(fy, ty), maxY = Math.max(fy, ty);
        double minZ = Math.min(fz, tz), maxZ = Math.max(fz, tz);

        return switch (face) {
            // Looking from -Z (north face has normal -Z, faces away from camera at default).
            case "north" -> new double[][]{
                    {maxX, maxY, minZ}, // top-left (u1, v1)
                    {minX, maxY, minZ}, // top-right
                    {minX, minY, minZ}, // bottom-right
                    {maxX, minY, minZ}  // bottom-left
            };
            case "south" -> new double[][]{
                    {minX, maxY, maxZ},
                    {maxX, maxY, maxZ},
                    {maxX, minY, maxZ},
                    {minX, minY, maxZ}
            };
            case "west" -> new double[][]{
                    {minX, maxY, minZ},
                    {minX, maxY, maxZ},
                    {minX, minY, maxZ},
                    {minX, minY, minZ}
            };
            case "east" -> new double[][]{
                    {maxX, maxY, maxZ},
                    {maxX, maxY, minZ},
                    {maxX, minY, minZ},
                    {maxX, minY, maxZ}
            };
            case "up" -> new double[][]{
                    {minX, maxY, minZ},
                    {maxX, maxY, minZ},
                    {maxX, maxY, maxZ},
                    {minX, maxY, maxZ}
            };
            case "down" -> new double[][]{
                    {minX, minY, maxZ},
                    {maxX, minY, maxZ},
                    {maxX, minY, minZ},
                    {minX, minY, minZ}
            };
            default -> new double[][]{{0, 0, 0}, {0, 0, 0}, {0, 0, 0}, {0, 0, 0}};
        };
    }

    /**
     * Default face UV per vanilla {@code FaceBakery#defaultFaceUV}, in the
     * raw 0..16 source-texture space (same as {@link FmmGeometryConverter}).
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

    // ------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------

    /**
     * Paints one face onto the canvas. Builds an {@link AffineTransform} that
     * maps the source-texture UV rect's corners to the destination
     * parallelogram, then draws the source image through it.
     *
     * <p>An affine transform is defined by where 3 points go; we use the
     * top-left, top-right, and bottom-left source corners (in pixel space) and
     * their destination screen positions. The face's 4th corner is implicit
     * (a parallelogram has only 3 degrees of freedom).
     */
    private static void drawFace(Graphics2D g, FaceDraw d) {
        double srcW = d.source.getWidth();
        double srcH = d.source.getHeight();
        // Java models declare UV in 0..16 space scaled to actual source-texture pixels.
        double u1px = d.uv[0] * srcW / 16.0;
        double v1px = d.uv[1] * srcH / 16.0;
        double u2px = d.uv[2] * srcW / 16.0;
        double v2px = d.uv[3] * srcH / 16.0;

        // Normalize so u1 <= u2 and v1 <= v2 -> the cropped source-pixel rectangle.
        // Track whether each axis was flipped so we can match the destination corner mapping.
        boolean flipU = u2px < u1px;
        boolean flipV = v2px < v1px;
        double sxMin = Math.min(u1px, u2px);
        double sxMax = Math.max(u1px, u2px);
        double syMin = Math.min(v1px, v2px);
        double syMax = Math.max(v1px, v2px);
        double srcRectW = sxMax - sxMin;
        double srcRectH = syMax - syMin;
        if (srcRectW <= 0 || srcRectH <= 0) return;

        // Crop a sub-image to the source UV rect, then place it on the canvas via the
        // affine. Snapping to int requires rounding; off-by-one slivers are negligible at
        // 64x64 output.
        int ix = (int) Math.floor(sxMin);
        int iy = (int) Math.floor(syMin);
        int iw = Math.max(1, (int) Math.ceil(srcRectW));
        int ih = Math.max(1, (int) Math.ceil(srcRectH));
        // Clamp to source bounds.
        if (ix < 0) ix = 0;
        if (iy < 0) iy = 0;
        if (ix + iw > d.source.getWidth()) iw = d.source.getWidth() - ix;
        if (iy + ih > d.source.getHeight()) ih = d.source.getHeight() - iy;
        if (iw <= 0 || ih <= 0) return;
        // Vertical-flipbook source PNGs (height > width) carry only frame 0 as a
        // square in the top portion. Mirror TextureStitcher's policy and read from the
        // top square only by NOT changing iy/ih beyond the source bounds (the source
        // image we read on cache load is unmodified; flipbook frame selection happens
        // implicitly because Java models reference UVs in 0..16 of the *first frame*,
        // which is the top square).
        BufferedImage sub;
        try {
            sub = d.source.getSubimage(ix, iy, iw, ih);
        } catch (Exception ex) {
            return;
        }

        // ---- Destination quad ----
        // d.screen[0]=corner-for-(u1,v1), [1]=(u2,v1), [2]=(u2,v2), [3]=(u1,v2).
        double[][] sc = d.screen;
        double[] dTL = sc[0];
        double[] dTR = sc[1];
        double[] dBR = sc[2];
        double[] dBL = sc[3];

        // The up/down faces' UV winding is mirrored vs side faces in Java's spec.
        // {@link FmmGeometryConverter#convertFaceUV} handles this on the Bedrock side
        // by emitting (u2,v2)+(u1-u2, v1-v2). Here we replicate the visual flip by
        // swapping the destination corners that get mapped to (u_min, v_min) vs
        // (u_max, v_max) when faceName is up/down.
        boolean isUpDown = d.faceName.equals("up") || d.faceName.equals("down");

        // Apply per-face UV rotation (0/90/180/270) by rotating the destination corner
        // mapping. faceRotation=90 means the texture's "top" goes to the face's right
        // edge etc. We rotate the (dTL,dTR,dBR,dBL) cycle by k quarter-turns.
        int k = d.faceRotation / 90;
        double[] tl = dTL, tr = dTR, br = dBR, bl = dBL;
        for (int i = 0; i < k; i++) {
            double[] tmp = tl;
            tl = bl;
            bl = br;
            br = tr;
            tr = tmp;
        }

        // Source-axis flips from the UV ordering ([u1 v1 u2 v2] with u2<u1 or v2<v1).
        // Apply by swapping destination corners along the corresponding axis.
        if (flipU) { double[] t = tl; tl = tr; tr = t; double[] t2 = bl; bl = br; br = t2; }
        if (flipV) { double[] t = tl; tl = bl; bl = t; double[] t2 = tr; tr = br; br = t2; }
        if (isUpDown) {
            // Swap top/bottom rows.
            double[] t = tl; tl = bl; bl = t;
            double[] t2 = tr; tr = br; br = t2;
        }

        // AffineTransform from sub-image (0,0)-(iw,ih) to (tl, tr, bl).
        // Affine math: solve for the matrix mapping (0,0)->tl, (iw,0)->tr, (0,ih)->bl.
        // m00 = (tr.x - tl.x) / iw, m10 = (tr.y - tl.y) / iw,
        // m01 = (bl.x - tl.x) / ih, m11 = (bl.y - tl.y) / ih,
        // m02 = tl.x, m12 = tl.y.
        double m00 = (tr[0] - tl[0]) / iw;
        double m10 = (tr[1] - tl[1]) / iw;
        double m01 = (bl[0] - tl[0]) / ih;
        double m11 = (bl[1] - tl[1]) / ih;
        double m02 = tl[0];
        double m12 = tl[1];
        AffineTransform at = new AffineTransform(m00, m10, m01, m11, m02, m12);

        g.drawImage(sub, at, null);
    }

    // ------------------------------------------------------------
    // Texture resolution
    // ------------------------------------------------------------

    /** Reads the {@code textures} block (Java model "textures" map). */
    private static Map<String, String> readTextureRefs(JsonObject model) {
        Map<String, String> refs = new HashMap<>();
        if (model.has("textures") && model.get("textures").isJsonObject()) {
            JsonObject t = model.getAsJsonObject("textures");
            for (Map.Entry<String, JsonElement> e : t.entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    refs.put(e.getKey(), e.getValue().getAsString());
                }
            }
        }
        return refs;
    }

    /**
     * Resolves a face's texture key ({@code "#0"}, {@code "#layer0"}, etc.)
     * to a concrete texture reference like {@code "elitemobs:items/bronzesword"}.
     * Follows the chain of {@code #}-prefixed redirects up to 8 hops to avoid
     * infinite loops on malformed models.
     */
    private static String resolveTextureRef(String key, Map<String, String> refs) {
        String current = key;
        for (int i = 0; i < 8; i++) {
            if (current == null) return null;
            if (!current.startsWith("#")) return current;
            String stripped = current.substring(1);
            String next = refs.get(stripped);
            if (next == null) next = refs.get(current);
            if (next == null || next.equals(current)) return null;
            current = next;
        }
        return null;
    }

    /**
     * Loads a texture from {@code assets/<ns>/textures/<path>.png} and caches it.
     * Returns null on missing file or read error. Flipbook textures (height &gt;
     * width) are kept as-is; the model's UV coordinates address the first frame
     * in the top square because Java models always declare UVs in 0..16 of that
     * frame.
     */
    private static BufferedImage loadTexture(String texRef, File mergedJavaPack,
                                             Map<String, BufferedImage> cache) {
        BufferedImage cached = cache.get(texRef);
        if (cached != null) return cached;
        int colon = texRef.indexOf(':');
        String ns = colon >= 0 ? texRef.substring(0, colon) : "minecraft";
        String path = colon >= 0 ? texRef.substring(colon + 1) : texRef;
        File f = new File(mergedJavaPack, "assets/" + ns + "/textures/" + path + ".png");
        if (!f.isFile()) {
            cache.put(texRef, null);
            return null;
        }
        try {
            BufferedImage img = ImageIO.read(f);
            // Vertical flipbook: keep only the top square frame so UV math
            // (which scales 0..16 -> source pixels) addresses frame 0.
            if (img != null && img.getHeight() > img.getWidth()) {
                img = img.getSubimage(0, 0, img.getWidth(), img.getWidth());
            }
            cache.put(texRef, img);
            return img;
        } catch (IOException e) {
            BedrockLog.warn("[IconRenderer] Failed to load texture " + f.getPath() + ": " + e.getMessage());
            cache.put(texRef, null);
            return null;
        }
    }

    // ------------------------------------------------------------
    // Vector / matrix math
    // ------------------------------------------------------------

    private static double[] readVec3(JsonObject obj, String key, double[] fallback) {
        if (!obj.has(key) || !obj.get(key).isJsonArray()) return fallback;
        JsonArray arr = obj.getAsJsonArray(key);
        if (arr.size() < 3) return fallback;
        return new double[]{
                arr.get(0).getAsDouble(),
                arr.get(1).getAsDouble(),
                arr.get(2).getAsDouble()
        };
    }

    private static double[] subtract(double[] v, double x, double y, double z) {
        return new double[]{v[0] - x, v[1] - y, v[2] - z};
    }

    private static double[] rotateX(double[] v, double angleDeg) {
        if (angleDeg == 0) return v;
        double r = Math.toRadians(angleDeg);
        double c = Math.cos(r), s = Math.sin(r);
        return new double[]{v[0], v[1] * c - v[2] * s, v[1] * s + v[2] * c};
    }

    private static double[] rotateY(double[] v, double angleDeg) {
        if (angleDeg == 0) return v;
        double r = Math.toRadians(angleDeg);
        double c = Math.cos(r), s = Math.sin(r);
        return new double[]{v[0] * c + v[2] * s, v[1], -v[0] * s + v[2] * c};
    }

    private static double[] rotateZ(double[] v, double angleDeg) {
        if (angleDeg == 0) return v;
        double r = Math.toRadians(angleDeg);
        double c = Math.cos(r), s = Math.sin(r);
        return new double[]{v[0] * c - v[1] * s, v[0] * s + v[1] * c, v[2]};
    }

    /**
     * Rotates a point around an arbitrary axis (must be unit length) anchored
     * at {@code origin}. Used for per-element rotation in Java models.
     */
    private static double[] rotateAroundAxis(double[] p, double[] axis, double angleDeg, double[] origin) {
        if (angleDeg == 0) return p;
        double[] q = subtract(p, origin[0], origin[1], origin[2]);
        // Axis is guaranteed unit (only x/y/z basis vectors are produced by the caller).
        if (axis[0] != 0) q = rotateX(q, angleDeg);
        else if (axis[1] != 0) q = rotateY(q, angleDeg);
        else if (axis[2] != 0) q = rotateZ(q, angleDeg);
        return new double[]{q[0] + origin[0], q[1] + origin[1], q[2] + origin[2]};
    }

    // ------------------------------------------------------------
    // Internal records
    // ------------------------------------------------------------

    /**
     * One face queued for painting: the cropped source image, its source-UV
     * rect (0..16 space), the 4 destination screen-pixel corners (in
     * {@code top-left, top-right, bottom-right, bottom-left} order matching
     * Java's UV winding), the average post-transform Z (painter sort key),
     * and any per-face UV rotation (degrees, multiple of 90).
     */
    private record FaceDraw(
            String faceName,
            BufferedImage source,
            double[] uv,
            double[][] screen,
            double avgZ,
            int faceRotation
    ) {}
}
