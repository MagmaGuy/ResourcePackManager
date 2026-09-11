package com.magmaguy.resourcepackmanager.mixer.engine;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finalizes every merged {@code blocks.json} atlas without consulting Minecraft client assets.
 *
 * <p>A directory sprite source is resolved by the client against every resource pack, including
 * the built-in vanilla pack. That makes a server-created atlas depend on client textures which
 * were never present in the merged pack. This finalizer replaces each directory source in place
 * with exact single sources for physical PNGs owned by the merged pack. Keeping each expansion at
 * the original array position preserves the effects of intervening filter and other sprite
 * sources.</p>
 *
 * <p>Five requested mip levels require both animation-frame axes to be divisible by sixteen.
 * Unsafe pack-owned textures are left untouched for fonts and other resource loaders. Atlas
 * singles instead point at a deterministic internal nearest-neighbour copy whose logical frame
 * axes are independently rounded up to multiples of sixteen, while retaining the original sprite
 * id and animation frame grid.</p>
 */
final class BlocksAtlasSanitizer {
    private static final Gson GSON = new Gson();
    private static final Pattern OVERLAY_DIRECTORY = Pattern.compile("[a-z0-9_-]+");
    private static final Pattern RESOURCE_NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern RESOURCE_PATH = Pattern.compile("[a-z0-9/._-]+");
    private static final String INTERNAL_NAMESPACE = "resourcepackmanager_internal";
    private static final String INTERNAL_PREFIX = "mip_safe/";
    private static final int REQUIRED_MIP_LEVELS = 5;
    private static final int REQUIRED_DIVISIBILITY = 1 << (REQUIRED_MIP_LEVELS - 1);

    private final MixerLogger logger;

    BlocksAtlasSanitizer(MixerLogger logger) {
        this.logger = logger;
    }

    void sanitize(File resourcePackRoot) throws IOException {
        List<Path> layerRoots = layerRoots(resourcePackRoot.toPath());
        rejectInternalNamespaceCollisions(layerRoots);
        Map<String, List<TextureVariant>> textures = collectTextures(layerRoots);
        SafeResourceResolver safeResources = new SafeResourceResolver(textures);

        for (Path layerRoot : layerRoots) {
            Path blocksAtlas = layerRoot.resolve("assets/minecraft/atlases/blocks.json");
            if (Files.isRegularFile(blocksAtlas)) sanitizeAtlas(blocksAtlas, textures, safeResources);
        }
    }

    private void sanitizeAtlas(Path blocksAtlas,
                               Map<String, List<TextureVariant>> textures,
                               SafeResourceResolver safeResources) throws IOException {
        JsonObject atlas = readJson(blocksAtlas, "canonical blocks atlas");
        if (!atlas.has("sources") || !atlas.get("sources").isJsonArray()) {
            throw new IOException("Canonical blocks atlas must contain a sources array: "
                    + blocksAtlas.toAbsolutePath());
        }

        var originalSources = atlas.getAsJsonArray("sources");
        var sanitizedSources = new com.google.gson.JsonArray();
        int directoryCount = 0;
        int generatedSingleCount = 0;
        int remappedSingleCount = 0;

        for (JsonElement sourceElement : originalSources) {
            DirectorySource directory = directorySource(sourceElement);
            if (directory != null) {
                directoryCount++;
                for (String resource : textures.keySet()) {
                    String path = resourcePath(resource);
                    String relativePath = directory.relativePath(path);
                    if (relativePath == null) continue;

                    String sprite = namespace(resource) + ":" + directory.prefix() + relativePath;
                    sanitizedSources.add(singleSource(
                            safeResources.resolve(resource),
                            sprite));
                    generatedSingleCount++;
                }
                continue;
            }

            JsonObject existingSingle = singleSource(sourceElement);
            if (existingSingle == null) {
                sanitizedSources.add(sourceElement);
                continue;
            }

            String resourceValue = stringProperty(existingSingle, "resource");
            String resource = canonicalResourceId(resourceValue);
            String safeResource = safeResources.resolve(resource);
            if (safeResource.equals(resource)) {
                sanitizedSources.add(sourceElement);
                continue;
            }

            JsonObject remapped = existingSingle.deepCopy();
            remapped.addProperty("resource", safeResource);
            if (!remapped.has("sprite")) remapped.addProperty("sprite", resource);
            sanitizedSources.add(remapped);
            remappedSingleCount++;
        }

        if (directoryCount == 0 && remappedSingleCount == 0) return;

        atlas.add("sources", sanitizedSources);
        writeJson(blocksAtlas, atlas);
        logger.collision("Sanitized " + directoryCount + " directory source(s) into "
                + generatedSingleCount + " pack-owned single source(s) and remapped "
                + remappedSingleCount + " unsafe existing single source(s): " + blocksAtlas);
    }

    private JsonObject singleSource(String resource, String sprite) {
        JsonObject single = new JsonObject();
        single.addProperty("type", "minecraft:single");
        single.addProperty("resource", resource);
        if (!resource.equals(sprite)) single.addProperty("sprite", sprite);
        return single;
    }

    private JsonObject singleSource(JsonElement sourceElement) {
        if (!sourceElement.isJsonObject()) return null;
        JsonObject source = sourceElement.getAsJsonObject();
        String type = stringProperty(source, "type");
        if (!"single".equals(type) && !"minecraft:single".equals(type)) return null;
        return stringProperty(source, "resource") == null ? null : source;
    }

    private DirectorySource directorySource(JsonElement sourceElement) {
        if (!sourceElement.isJsonObject()) return null;
        JsonObject source = sourceElement.getAsJsonObject();
        String type = stringProperty(source, "type");
        if (!"directory".equals(type) && !"minecraft:directory".equals(type)) return null;

        String sourcePath = stringProperty(source, "source");
        String prefix = stringProperty(source, "prefix");
        return sourcePath == null || prefix == null ? null : new DirectorySource(sourcePath, prefix);
    }

    private Map<String, List<TextureVariant>> collectTextures(List<Path> layerRoots) throws IOException {
        Map<String, List<TextureVariant>> textures = new TreeMap<>();
        for (Path layerRoot : layerRoots) {
            Path assets = layerRoot.resolve("assets");
            if (!Files.isDirectory(assets)) continue;

            for (Path namespaceDirectory : sortedDirectories(assets)) {
                String namespace = namespaceDirectory.getFileName().toString();
                if (INTERNAL_NAMESPACE.equals(namespace)) continue;
                if (!RESOURCE_NAMESPACE.matcher(namespace).matches()) continue;

                Path textureRoot = namespaceDirectory.resolve("textures");
                if (!Files.isDirectory(textureRoot)) continue;

                try (Stream<Path> paths = Files.walk(textureRoot)) {
                    paths.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().endsWith(".png"))
                            .sorted(Comparator.comparing(Path::toString))
                            .forEach(path -> {
                                String relative = textureRoot.relativize(path).toString().replace('\\', '/');
                                String resourcePath = relative.substring(0, relative.length() - ".png".length());
                                if (!RESOURCE_PATH.matcher(resourcePath).matches()) return;
                                String resource = namespace + ":" + resourcePath;
                                textures.computeIfAbsent(resource, ignored -> new ArrayList<>())
                                        .add(new TextureVariant(layerRoot, path));
                            });
                }
            }
        }
        return textures;
    }

    private List<Path> layerRoots(Path resourcePackRoot) throws IOException {
        Map<Path, Path> roots = new LinkedHashMap<>();
        Path normalizedRoot = resourcePackRoot.toAbsolutePath().normalize();
        roots.put(normalizedRoot, normalizedRoot);

        Path packMcmeta = normalizedRoot.resolve("pack.mcmeta");
        if (!Files.isRegularFile(packMcmeta)) return new ArrayList<>(roots.values());

        JsonObject metadata = readJson(packMcmeta, "pack metadata");
        if (!metadata.has("overlays") || !metadata.get("overlays").isJsonObject()) {
            return new ArrayList<>(roots.values());
        }
        JsonObject overlays = metadata.getAsJsonObject("overlays");
        if (!overlays.has("entries") || !overlays.get("entries").isJsonArray()) {
            return new ArrayList<>(roots.values());
        }

        for (JsonElement entryElement : overlays.getAsJsonArray("entries")) {
            if (!entryElement.isJsonObject()) continue;
            String directory = stringProperty(entryElement.getAsJsonObject(), "directory");
            if (directory == null || !OVERLAY_DIRECTORY.matcher(directory).matches()) continue;
            Path overlayRoot = normalizedRoot.resolve(directory).normalize();
            roots.put(overlayRoot, overlayRoot);
        }
        return new ArrayList<>(roots.values());
    }

    private void rejectInternalNamespaceCollisions(List<Path> layerRoots) throws IOException {
        for (Path layerRoot : layerRoots) {
            Path internalNamespace = layerRoot.resolve("assets").resolve(INTERNAL_NAMESPACE);
            if (Files.exists(internalNamespace)) {
                throw new IOException("Merged pack already contains RSPM's reserved internal namespace: "
                        + internalNamespace.toAbsolutePath());
            }
        }
    }

    private List<Path> sortedDirectories(Path parent) throws IOException {
        if (!Files.isDirectory(parent)) return List.of();
        try (Stream<Path> children = Files.list(parent)) {
            return children.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    private JsonObject readJson(Path path, String description) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                throw new IOException("Expected a JSON object in " + description + ": " + path.toAbsolutePath());
            }
            return parsed.getAsJsonObject();
        } catch (Exception exception) {
            if (exception instanceof IOException ioException
                    && ioException.getMessage() != null
                    && ioException.getMessage().contains(path.toAbsolutePath().toString())) {
                throw ioException;
            }
            throw new IOException("Unable to parse " + description + ": " + path.toAbsolutePath(), exception);
        }
    }

    private void writeJson(Path path, JsonObject json) throws IOException {
        try (Writer writer = new BufferedWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8), 1 << 16)) {
            GSON.toJson(json, writer);
        }
    }

    private String stringProperty(JsonObject object, String property) {
        JsonElement value = object.get(property);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString()
                : null;
    }

    private String canonicalResourceId(String resource) {
        return resource.indexOf(':') < 0 ? "minecraft:" + resource : resource;
    }

    private String namespace(String resource) {
        return resource.substring(0, resource.indexOf(':'));
    }

    private String resourcePath(String resource) {
        return resource.substring(resource.indexOf(':') + 1);
    }

    private final class SafeResourceResolver {
        private final Map<String, List<TextureVariant>> textures;
        private final Map<String, String> resolutions = new TreeMap<>();

        private SafeResourceResolver(Map<String, List<TextureVariant>> textures) {
            this.textures = textures;
        }

        private String resolve(String resource) throws IOException {
            String existing = resolutions.get(resource);
            if (existing != null) return existing;

            List<TextureVariant> variants = textures.get(resource);
            if (variants == null) {
                resolutions.put(resource, resource);
                return resource;
            }

            List<AnalyzedVariant> analyzedVariants = new ArrayList<>();
            boolean requiresSafeResource = false;
            for (TextureVariant variant : variants) {
                AnalyzedVariant analyzed = analyze(variant);
                analyzedVariants.add(analyzed);
                requiresSafeResource |= analyzed.resize().required();
            }
            if (!requiresSafeResource) {
                resolutions.put(resource, resource);
                return resource;
            }

            String safeResource = INTERNAL_NAMESPACE + ":" + INTERNAL_PREFIX + resource.replace(':', '/');
            for (AnalyzedVariant variant : analyzedVariants) {
                materializeSafeVariant(resource, variant);
            }
            resolutions.put(resource, safeResource);
            return safeResource;
        }

        private AnalyzedVariant analyze(TextureVariant variant) throws IOException {
            BufferedImage image;
            try {
                image = ImageIO.read(variant.png().toFile());
            } catch (IOException exception) {
                throw new IOException("Unable to decode emitted blocks-atlas PNG: "
                        + variant.png().toAbsolutePath(), exception);
            }
            if (image == null) {
                throw new IOException("Unable to decode emitted blocks-atlas PNG: "
                        + variant.png().toAbsolutePath());
            }

            JsonObject metadata = readOptionalMetadata(variant.metadata());
            FrameSize frame = effectiveFrameSize(image, metadata, variant.metadata());
            FrameSize safeFrame = new FrameSize(
                    roundFrameAxis(frame.width(), variant.png()),
                    roundFrameAxis(frame.height(), variant.png()));
            ResizePlan resize = new ResizePlan(
                    safeFrame,
                    scaledSheetAxis(image.getWidth(), frame.width(), safeFrame.width(), "width", variant.png()),
                    scaledSheetAxis(image.getHeight(), frame.height(), safeFrame.height(), "height", variant.png()),
                    !safeFrame.equals(frame));
            return new AnalyzedVariant(variant, image, metadata, resize);
        }

        private int roundFrameAxis(int frameAxis, Path texture) throws IOException {
            try {
                long rounded = Math.multiplyExact(
                        Math.floorDiv(Math.addExact((long) frameAxis, REQUIRED_DIVISIBILITY - 1),
                                REQUIRED_DIVISIBILITY),
                        (long) REQUIRED_DIVISIBILITY);
                if (rounded > Integer.MAX_VALUE) throw new ArithmeticException("rounded frame axis exceeds int");
                return (int) rounded;
            } catch (ArithmeticException exception) {
                throw new IOException("Logical animation frame dimensions overflow for emitted blocks-atlas PNG: "
                        + texture.toAbsolutePath(), exception);
            }
        }

        private int scaledSheetAxis(int sheetAxis,
                                    int frameAxis,
                                    int safeFrameAxis,
                                    String axisName,
                                    Path texture) throws IOException {
            try {
                long numerator = Math.multiplyExact((long) sheetAxis, (long) safeFrameAxis);
                if (numerator % frameAxis != 0) {
                    throw new IOException("Texture sheet " + axisName
                            + " cannot represent its exact rational frame resize: " + texture.toAbsolutePath());
                }
                long scaled = numerator / frameAxis;
                if (scaled > Integer.MAX_VALUE) throw new ArithmeticException("scaled sheet axis exceeds int");
                return (int) scaled;
            } catch (ArithmeticException exception) {
                throw new IOException("Texture sheet dimensions overflow while creating mip-safe copy: "
                        + texture.toAbsolutePath(), exception);
            }
        }

        private void materializeSafeVariant(String resource, AnalyzedVariant analyzed) throws IOException {
            TextureVariant variant = analyzed.variant();
            Path destination = variant.layerRoot()
                    .resolve("assets")
                    .resolve(INTERNAL_NAMESPACE)
                    .resolve("textures")
                    .resolve(INTERNAL_PREFIX)
                    .resolve(namespace(resource))
                    .resolve(resourcePath(resource) + ".png");
            Files.createDirectories(destination.getParent());

            if (!analyzed.resize().required()) {
                Files.copy(variant.png(), destination, StandardCopyOption.REPLACE_EXISTING);
            } else {
                writeNearestNeighbourCopy(
                        analyzed.image(),
                        analyzed.resize().sheetWidth(),
                        analyzed.resize().sheetHeight(),
                        destination);
            }

            Path sourceMetadata = variant.metadata();
            Path destinationMetadata = Path.of(destination + ".mcmeta");
            if (!Files.isRegularFile(sourceMetadata)) {
                Files.deleteIfExists(destinationMetadata);
                return;
            }
            if (!analyzed.resize().required()) {
                Files.copy(sourceMetadata, destinationMetadata, StandardCopyOption.REPLACE_EXISTING);
                return;
            }

            JsonObject scaledMetadata = analyzed.metadata().deepCopy();
            scaleExplicitFrameDimensions(scaledMetadata, analyzed.resize().safeFrame(), sourceMetadata);
            writeJson(destinationMetadata, scaledMetadata);
        }

        private JsonObject readOptionalMetadata(Path metadata) throws IOException {
            if (!Files.isRegularFile(metadata)) return null;
            return readJson(metadata, "animation metadata");
        }

        private FrameSize effectiveFrameSize(BufferedImage image,
                                             JsonObject metadata,
                                             Path metadataPath) throws IOException {
            JsonObject animation = animationSection(metadata, metadataPath);
            if (animation == null) return new FrameSize(image.getWidth(), image.getHeight());

            Integer width = positiveInteger(animation, "width", metadataPath);
            Integer height = positiveInteger(animation, "height", metadataPath);
            if (width != null && height != null) return new FrameSize(width, height);
            if (width != null) return new FrameSize(width, image.getHeight());
            if (height != null) return new FrameSize(image.getWidth(), height);
            int side = Math.min(image.getWidth(), image.getHeight());
            return new FrameSize(side, side);
        }

        private void scaleExplicitFrameDimensions(JsonObject metadata,
                                                  FrameSize safeFrame,
                                                  Path sourceMetadata) throws IOException {
            JsonObject animation = animationSection(metadata, sourceMetadata);
            if (animation == null) return;
            setExplicitFrameDimension(animation, "width", safeFrame.width(), sourceMetadata);
            setExplicitFrameDimension(animation, "height", safeFrame.height(), sourceMetadata);
        }

        private void setExplicitFrameDimension(JsonObject animation,
                                               String property,
                                               int safeValue,
                                               Path sourceMetadata) throws IOException {
            Integer value = positiveInteger(animation, property, sourceMetadata);
            if (value == null) return;
            animation.addProperty(property, safeValue);
        }

        private JsonObject animationSection(JsonObject metadata, Path metadataPath) throws IOException {
            if (metadata == null || !metadata.has("animation")) return null;
            if (!metadata.get("animation").isJsonObject()) {
                throw new IOException("Animation metadata section must be a JSON object: "
                        + metadataPath.toAbsolutePath());
            }
            return metadata.getAsJsonObject("animation");
        }

        private Integer positiveInteger(JsonObject object,
                                        String property,
                                        Path metadataPath) throws IOException {
            if (!object.has(property)) return null;
            JsonElement value = object.get(property);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                throw new IOException("Animation " + property + " must be a positive integer: "
                        + metadataPath.toAbsolutePath());
            }
            try {
                int integer = value.getAsBigDecimal().intValueExact();
                if (integer > 0) return integer;
            } catch (ArithmeticException | NumberFormatException exception) {
            }
            throw new IOException("Animation " + property + " must be a positive integer: "
                    + metadataPath.toAbsolutePath());
        }

        private void writeNearestNeighbourCopy(BufferedImage source,
                                               int width,
                                               int height,
                                               Path destination)
                throws IOException {
            try {
                Math.multiplyExact(width, height);
            } catch (ArithmeticException exception) {
                throw new IOException("Texture pixel count overflows while creating mip-safe copy: "
                        + destination.toAbsolutePath(), exception);
            }

            final BufferedImage scaled;
            try {
                scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            } catch (IllegalArgumentException exception) {
                throw new IOException("Unable to allocate mip-safe texture dimensions for: "
                        + destination.toAbsolutePath(), exception);
            }
            int[] sourceRow = new int[source.getWidth()];
            int[] scaledRow = new int[width];
            int loadedSourceY = -1;
            for (int destinationY = 0; destinationY < height; destinationY++) {
                int sourceY = (int) ((long) destinationY * source.getHeight() / height);
                if (sourceY != loadedSourceY) {
                    source.getRGB(0, sourceY, source.getWidth(), 1, sourceRow, 0, source.getWidth());
                    loadedSourceY = sourceY;
                }
                for (int destinationX = 0; destinationX < width; destinationX++) {
                    int sourceX = (int) ((long) destinationX * source.getWidth() / width);
                    scaledRow[destinationX] = sourceRow[sourceX];
                }
                scaled.setRGB(0, destinationY, width, 1, scaledRow, 0, width);
            }

            if (!ImageIO.write(scaled, "png", destination.toFile())) {
                throw new IOException("PNG writer unavailable while creating mip-safe copy: " + destination);
            }
        }
    }

    private record DirectorySource(String source, String prefix) {
        private String relativePath(String resourcePath) {
            if (source.isEmpty()) return resourcePath;
            String sourcePrefix = source + "/";
            return resourcePath.startsWith(sourcePrefix)
                    ? resourcePath.substring(sourcePrefix.length())
                    : null;
        }
    }

    private record TextureVariant(Path layerRoot, Path png) {
        private Path metadata() {
            return Path.of(png + ".mcmeta");
        }
    }

    private record AnalyzedVariant(TextureVariant variant,
                                   BufferedImage image,
                                   JsonObject metadata,
                                   ResizePlan resize) {
    }

    private record FrameSize(int width, int height) {
    }

    private record ResizePlan(FrameSize safeFrame, int sheetWidth, int sheetHeight, boolean required) {
    }
}
