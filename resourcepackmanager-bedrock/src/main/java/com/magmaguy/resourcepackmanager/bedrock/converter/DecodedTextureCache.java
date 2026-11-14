package com.magmaguy.resourcepackmanager.bedrock.converter;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decoded source textures for one conversion run, shared by {@link TextureStitcher} and
 * {@link IconRenderer} so a texture used by many models, or by a model's atlas and its icon, is
 * decoded once. The merged Java pack does not change during a conversion, so a cached image is
 * exactly what a fresh {@link ImageIO#read(File)} would return. Callers only read the images.
 *
 * <p>Only successful decodes are kept: a missing or unreadable file is read again on the next
 * request, so its warnings appear as often as before. The cache keeps the most recently used
 * images up to a pixel budget.</p>
 */
public final class DecodedTextureCache {
    private static final long PIXEL_BUDGET = 16L * 1024 * 1024;

    private final Map<File, BufferedImage> images = new LinkedHashMap<>(64, 0.75f, true);
    private long cachedPixels;

    /** {@link ImageIO#read(File)}, answered from the cache when this file was decoded before. */
    public BufferedImage read(File file) throws IOException {
        BufferedImage cached = images.get(file);
        if (cached != null) return cached;
        BufferedImage image = ImageIO.read(file);
        if (image == null) return null;
        long pixels = (long) image.getWidth() * image.getHeight();
        if (pixels <= PIXEL_BUDGET) {
            images.put(file, image);
            cachedPixels += pixels;
            Iterator<BufferedImage> eldest = images.values().iterator();
            while (cachedPixels > PIXEL_BUDGET && eldest.hasNext()) {
                BufferedImage evicted = eldest.next();
                if (evicted == image) break;
                cachedPixels -= (long) evicted.getWidth() * evicted.getHeight();
                eldest.remove();
            }
        }
        return image;
    }
}
