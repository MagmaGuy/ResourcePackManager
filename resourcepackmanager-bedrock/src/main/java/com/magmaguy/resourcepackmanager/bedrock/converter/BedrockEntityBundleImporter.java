package com.magmaguy.resourcepackmanager.bedrock.converter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.magmaguy.resourcepackmanager.bedrock.BedrockLog;
import com.magmaguy.resourcepackmanager.bedrock.util.BedrockShortName;
import com.magmaguy.resourcepackmanager.mixer.engine.internal.Cancellation;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

public final class BedrockEntityBundleImporter {
    public static final String BUNDLE_ROOT = "rspm_bedrock_pack";

    private static final int GEYSER_PATH_WARNING_LENGTH = 80;
    private static final String ENTITY_TEXTURE_PREFIX = "textures/entity/";
    private static final String ENTITY_MODEL_PREFIX = "models/entity/";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static final Set<String> ALLOWED_TOP_LEVEL = Set.of(
            "entity",
            "models",
            "animations",
            "animation_controllers",
            "render_controllers",
            "materials",
            "textures"
    );

    private BedrockEntityBundleImporter() {
    }

    /**
     * Copies first-party Bedrock entity bundle files from
     * {@code assets/<namespace>/rspm_bedrock_pack/**} into the generated Bedrock
     * pack. The importer intentionally allows only entity-related Bedrock
     * directories so producer plugins cannot accidentally shadow unrelated pack
     * files such as manifest.json or item_texture.json.
     *
     * @return number of files copied
     */
    public static int importBundles(File mergedJavaPack, File bedrockPackDir)
            throws IOException {
        return importBundles(mergedJavaPack, bedrockPackDir, () -> false);
    }

    public static int importBundles(File mergedJavaPack, File bedrockPackDir,
                                    BooleanSupplier cancellationRequested) throws IOException {
        File assetsDir = new File(mergedJavaPack, "assets");
        if (!assetsDir.isDirectory()) {
            return 0;
        }

        File[] namespaces = assetsDir.listFiles(File::isDirectory);
        if (namespaces == null || namespaces.length == 0) {
            return 0;
        }
        Arrays.sort(namespaces, Comparator.comparing(File::getName));

        AtomicInteger copied = new AtomicInteger();
        for (File namespace : namespaces) {
            if (isCancelled(cancellationRequested)) return copied.get();
            Path bundleRoot = namespace.toPath().resolve(BUNDLE_ROOT);
            if (!Files.isDirectory(bundleRoot)) {
                continue;
            }
            copied.addAndGet(copyBundleRoot(
                    bundleRoot,
                    bedrockPackDir.toPath(),
                    cancellationRequested));
        }
        if (copied.get() > 0) {
            BedrockLog.debug("[BedrockConverter] Imported " + copied.get() + " Bedrock custom entity bundle files.");
        }
        return copied.get();
    }

    private static int copyBundleRoot(Path bundleRoot, Path bedrockPackRoot,
                                      BooleanSupplier cancellationRequested) throws IOException {
        AtomicInteger copied = new AtomicInteger();
        List<Path> sources;
        try (Stream<Path> files = Files.walk(bundleRoot)) {
            sources = files
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(source -> normalizeRelative(bundleRoot.relativize(source))))
                    .toList();
        }

        Map<String, String> pathRewrites = buildLongPathRewrites(bundleRoot, sources);
        Map<String, String> referenceRewrites = buildReferenceRewrites(pathRewrites);

        for (Path source : sources) {
            if (isCancelled(cancellationRequested)) return copied.get();
            Path relative = bundleRoot.relativize(source).normalize();
            String relativeName = normalizeRelative(relative);
            if (!isAllowed(relative)) {
                BedrockLog.warn("[BedrockConverter] Skipping unsupported Bedrock entity bundle path "
                        + relative + " under " + bundleRoot);
                continue;
            }

            String destinationName = pathRewrites.getOrDefault(relativeName, relativeName);
            Path destination = bedrockPackRoot.resolve(destinationName).normalize();
            if (!destination.startsWith(bedrockPackRoot.normalize())) {
                throw new IOException("Unsafe Bedrock entity bundle path " + relative);
            }

            Files.createDirectories(destination.getParent());
            Path temporary = destination.resolveSibling(
                    "." + destination.getFileName() + "." + UUID.randomUUID() + ".tmp");
            try {
                if (!referenceRewrites.isEmpty() && isJsonFile(relativeName)) {
                    copyJsonWithReferenceRewrites(source, temporary, referenceRewrites);
                } else {
                    Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
                }
                if (Files.isRegularFile(destination)) {
                    if (Files.mismatch(temporary, destination) != -1L) {
                        throw new IOException("Conflicting Bedrock entity bundle output "
                                + destination + " from " + source);
                    }
                } else {
                    try {
                        Files.move(temporary, destination,
                                StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException atomicMoveFailed) {
                        Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            copied.incrementAndGet();
        }
        return copied.get();
    }

    private static Map<String, String> buildLongPathRewrites(Path bundleRoot, List<Path> sources) {
        Map<String, String> rewrites = new LinkedHashMap<>();
        Set<String> usedOutputPaths = new HashSet<>();
        for (Path source : sources) {
            usedOutputPaths.add(normalizeRelative(bundleRoot.relativize(source)));
        }

        for (Path source : sources) {
            String relative = normalizeRelative(bundleRoot.relativize(source));
            if (relative.length() < GEYSER_PATH_WARNING_LENGTH) {
                continue;
            }
            String destinationPrefix = compactDestinationPrefix(relative);
            if (destinationPrefix == null) {
                continue;
            }

            String suffix = compactFileSuffix(relative);
            String destination;
            int attempt = 0;
            do {
                String hashInput = attempt == 0 ? relative : relative + "|" + attempt;
                destination = destinationPrefix + BedrockShortName.shortHash(hashInput) + suffix;
                attempt++;
            } while (usedOutputPaths.contains(destination));

            rewrites.put(relative, destination);
            usedOutputPaths.add(destination);
        }

        if (!rewrites.isEmpty()) {
            BedrockLog.debug("[BedrockConverter] Shortened " + rewrites.size()
                    + " Bedrock entity bundle path(s) to avoid Geyser's "
                    + GEYSER_PATH_WARNING_LENGTH + "-character pack path warning.");
        }
        return rewrites;
    }

    private static Map<String, String> buildReferenceRewrites(Map<String, String> pathRewrites) {
        Map<String, String> rewrites = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : pathRewrites.entrySet()) {
            rewrites.put(entry.getKey(), entry.getValue());
            rewrites.put(stripExtension(entry.getKey()), stripExtension(entry.getValue()));
            rewrites.put(stripCompactSuffix(entry.getKey()), stripCompactSuffix(entry.getValue()));
        }
        return rewrites;
    }

    private static void copyJsonWithReferenceRewrites(Path source, Path destination,
                                                      Map<String, String> referenceRewrites) throws IOException {
        try (Reader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8);
             Writer writer = Files.newBufferedWriter(destination, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            GSON.toJson(rewriteJsonStrings(root, referenceRewrites), writer);
        } catch (Exception parseException) {
            Files.deleteIfExists(destination);
            throw new IOException("Failed to parse Bedrock bundle JSON " + source,
                    parseException);
        }
    }

    private static JsonElement rewriteJsonStrings(JsonElement element, Map<String, String> referenceRewrites) {
        if (element == null || element.isJsonNull()) {
            return element;
        }
        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (!primitive.isString()) {
                return element;
            }
            String replacement = referenceRewrites.get(primitive.getAsString());
            return replacement == null ? element : new JsonPrimitive(replacement);
        }
        if (element.isJsonArray()) {
            JsonArray rewritten = new JsonArray();
            for (JsonElement child : element.getAsJsonArray()) {
                rewritten.add(rewriteJsonStrings(child, referenceRewrites));
            }
            return rewritten;
        }
        if (element.isJsonObject()) {
            JsonObject rewritten = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                String key = referenceRewrites.getOrDefault(entry.getKey(), entry.getKey());
                rewritten.add(key, rewriteJsonStrings(entry.getValue(), referenceRewrites));
            }
            return rewritten;
        }
        return element;
    }

    private static boolean isAllowed(Path relative) {
        if (relative.getNameCount() < 2) {
            return false;
        }
        String first = relative.getName(0).toString().replace('\\', '/');
        if (!ALLOWED_TOP_LEVEL.contains(first)) {
            return false;
        }
        if ("models".equals(first)) {
            return relative.getNameCount() >= 3 && "entity".equals(relative.getName(1).toString());
        }
        if ("textures".equals(first)) {
            return relative.getNameCount() >= 3 && "entity".equals(relative.getName(1).toString());
        }
        return true;
    }

    private static boolean isEntityTextureFile(String relative) {
        String lower = relative.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith(ENTITY_TEXTURE_PREFIX)
                && (lower.endsWith(".png") || lower.endsWith(".tga"));
    }

    private static String compactDestinationPrefix(String relative) {
        String lower = relative.toLowerCase(java.util.Locale.ROOT);
        if (isEntityTextureFile(lower)) {
            return ENTITY_TEXTURE_PREFIX;
        }
        if (lower.startsWith(ENTITY_MODEL_PREFIX)) {
            return ENTITY_MODEL_PREFIX;
        }
        int slash = lower.indexOf('/');
        String top = slash >= 0 ? lower.substring(0, slash) : lower;
        return switch (top) {
            case "entity" -> "entity/";
            case "animations" -> "animations/";
            case "animation_controllers" -> "animation_controllers/";
            case "render_controllers" -> "render_controllers/";
            case "materials" -> "materials/";
            case "particles" -> "particles/";
            default -> null;
        };
    }

    private static String compactFileSuffix(String relative) {
        String lower = relative.toLowerCase(java.util.Locale.ROOT);
        for (String suffix : List.of(
                ".animation_controllers.json",
                ".render_controllers.json",
                ".animation.json",
                ".entity.json",
                ".particle.json",
                ".geo.json")) {
            if (lower.endsWith(suffix)) {
                return suffix;
            }
        }
        return extensionOf(relative);
    }

    private static boolean isJsonFile(String relative) {
        return relative.toLowerCase(java.util.Locale.ROOT).endsWith(".json");
    }

    private static String extensionOf(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return dot > slash ? path.substring(dot) : "";
    }

    private static String stripExtension(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return dot > slash ? path.substring(0, dot) : path;
    }

    private static String stripCompactSuffix(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        for (String suffix : List.of(
                ".animation_controllers.json",
                ".render_controllers.json",
                ".animation.json",
                ".entity.json",
                ".particle.json",
                ".geo.json")) {
            if (lower.endsWith(suffix)) {
                return path.substring(0, path.length() - suffix.length());
            }
        }
        return stripExtension(path);
    }

    private static String normalizeRelative(Path path) {
        return path.normalize().toString().replace('\\', '/');
    }

    private static boolean isCancelled(BooleanSupplier cancellationRequested) {
        return Cancellation.isCancelled(cancellationRequested);
    }
}
