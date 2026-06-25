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

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
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
    public static int importBundles(File mergedJavaPack, File bedrockPackDir) {
        File assetsDir = new File(mergedJavaPack, "assets");
        if (!assetsDir.isDirectory()) {
            return 0;
        }

        File[] namespaces = assetsDir.listFiles(File::isDirectory);
        if (namespaces == null || namespaces.length == 0) {
            return 0;
        }

        AtomicInteger copied = new AtomicInteger();
        for (File namespace : namespaces) {
            Path bundleRoot = namespace.toPath().resolve(BUNDLE_ROOT);
            if (!Files.isDirectory(bundleRoot)) {
                continue;
            }
            copied.addAndGet(copyBundleRoot(bundleRoot, bedrockPackDir.toPath()));
        }
        if (copied.get() > 0) {
            BedrockLog.debug("[BedrockConverter] Imported " + copied.get() + " Bedrock custom entity bundle files.");
        }
        return copied.get();
    }

    private static int copyBundleRoot(Path bundleRoot, Path bedrockPackRoot) {
        AtomicInteger copied = new AtomicInteger();
        List<Path> sources;
        try (Stream<Path> files = Files.walk(bundleRoot)) {
            sources = files
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(source -> normalizeRelative(bundleRoot.relativize(source))))
                    .toList();
        } catch (IOException exception) {
            BedrockLog.warn("[BedrockConverter] Failed to scan Bedrock entity bundle root "
                    + bundleRoot + ": " + exception.getMessage());
            return 0;
        }

        Map<String, String> pathRewrites = buildLongPathRewrites(bundleRoot, sources);
        Map<String, String> referenceRewrites = buildReferenceRewrites(pathRewrites);

        for (Path source : sources) {
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
                BedrockLog.warn("[BedrockConverter] Skipping unsafe Bedrock entity bundle path " + relative);
                continue;
            }

            try {
                Files.createDirectories(destination.getParent());
                if (!referenceRewrites.isEmpty() && isJsonFile(relativeName)) {
                    copyJsonWithReferenceRewrites(source, destination, referenceRewrites);
                } else {
                    Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
                }
                copied.incrementAndGet();
            } catch (IOException exception) {
                BedrockLog.warn("[BedrockConverter] Failed to copy Bedrock entity bundle file "
                        + source + ": " + exception.getMessage());
            }
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
            String rewritten = rewriteText(Files.readString(source, StandardCharsets.UTF_8), referenceRewrites);
            Files.writeString(destination, rewritten, StandardCharsets.UTF_8);
            BedrockLog.debug("[BedrockConverter] Rewrote Bedrock bundle references in "
                    + source + " using text fallback: " + parseException.getMessage());
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

    private static String rewriteText(String text, Map<String, String> referenceRewrites) {
        String rewritten = text;
        for (Map.Entry<String, String> entry : referenceRewrites.entrySet()) {
            rewritten = rewritten.replace(entry.getKey(), entry.getValue());
        }
        return rewritten;
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
}
