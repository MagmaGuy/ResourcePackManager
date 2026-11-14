package com.magmaguy.resourcepackmanager.mixer.bedrock;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.magmaguy.resourcepackmanager.mixer.engine.MixerLogger;
import com.magmaguy.resourcepackmanager.mixer.engine.internal.Cancellation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * Losslessly consolidates exact duplicate texture files in a generated Bedrock
 * pack. Bedrock JSON refers to textures by path, so duplicate bytes can share a
 * single canonical file once every exact path reference has been rewritten.
 *
 * <p>This intentionally does not perform perceptual image matching, PNG
 * re-encoding, or deduplication outside {@code textures/}. Only byte-identical
 * images with the same file extension are candidates. If an alias occurs in a
 * malformed JSON file, a non-JSON file, or as part of a larger JSON string, the
 * alias file is retained because that reference cannot be rewritten with full
 * structural confidence.</p>
 */
public final class BedrockPackOptimizer {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(".png", ".tga", ".jpg", ".jpeg");
    private static final int OPAQUE_SCAN_CHUNK_BYTES = 1 << 16;

    private BedrockPackOptimizer() {
    }

    public static void deduplicateExactTextures(Path packRoot, MixerLogger logger) {
        deduplicateExactTextures(packRoot, logger, () -> false);
    }

    public static void deduplicateExactTextures(Path packRoot,
                                                MixerLogger logger,
                                                BooleanSupplier cancellationRequested) {
        if (packRoot == null || !Files.isDirectory(packRoot)) return;
        if (isCancelled(cancellationRequested)) return;
        Path normalizedRoot = packRoot.toAbsolutePath().normalize();
        Path texturesRoot = normalizedRoot.resolve("textures");
        if (!Files.isDirectory(texturesRoot)) return;

        try {
            List<Path> textureFiles;
            try (Stream<Path> stream = Files.walk(texturesRoot)) {
                textureFiles = stream
                        .filter(Files::isRegularFile)
                        .filter(BedrockPackOptimizer::isImage)
                        .sorted(Comparator.comparing(path -> relative(normalizedRoot, path)))
                        .toList();
            }

            Map<ExactTextureFingerprint, Path> canonicalByFingerprint = new HashMap<>();
            Map<String, Replacement> replacements = new LinkedHashMap<>();
            Map<Path, Long> duplicateSizes = new LinkedHashMap<>();
            for (Path texture : textureFiles) {
                if (isCancelled(cancellationRequested)) return;
                ExactTextureFingerprint fingerprint = ExactTextureFingerprint.of(texture);
                Path canonical = canonicalByFingerprint.putIfAbsent(fingerprint, texture);
                if (canonical == null) continue;

                String duplicateRelative = relative(normalizedRoot, texture);
                String canonicalRelative = relative(normalizedRoot, canonical);
                Replacement replacement = new Replacement(texture,
                        duplicateRelative, canonicalRelative);
                replacements.put(duplicateRelative, replacement);
                replacements.put(stripExtension(duplicateRelative), replacement.withoutExtension());
                duplicateSizes.put(texture, Files.size(texture));
            }
            if (duplicateSizes.isEmpty()) return;
            AliasMatcher aliasMatcher = new AliasMatcher(replacements);

            Set<Path> protectedDuplicates = new LinkedHashSet<>();
            List<Path> packFiles;
            try (Stream<Path> stream = Files.walk(normalizedRoot)) {
                packFiles = stream
                        .filter(Files::isRegularFile)
                        .sorted(Comparator.comparing(path -> relative(normalizedRoot, path)))
                        .toList();
            }

            int jsonFilesRewritten = 0;
            for (Path file : packFiles) {
                if (isCancelled(cancellationRequested)) return;
                if (duplicateSizes.containsKey(file)) continue;
                String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (lower.endsWith(".json")) {
                    if (inspectAndRewriteJson(file, replacements, aliasMatcher,
                            protectedDuplicates)) {
                        jsonFilesRewritten++;
                    }
                } else if (!isImage(file)) {
                    protectAliasesFoundInOpaqueFile(file, aliasMatcher, protectedDuplicates);
                }
            }

            int removed = 0;
            long bytesRemoved = 0L;
            for (Map.Entry<Path, Long> duplicate : duplicateSizes.entrySet()) {
                if (isCancelled(cancellationRequested)) return;
                if (protectedDuplicates.contains(duplicate.getKey())) continue;
                if (Files.deleteIfExists(duplicate.getKey())) {
                    removed++;
                    bytesRemoved += duplicate.getValue();
                }
            }
            removeEmptyDirectories(texturesRoot);

            if (removed > 0 && logger != null) {
                logger.info("[BedrockPackOptimizer] Consolidated " + removed
                        + " exact duplicate texture file(s), removing " + bytesRemoved
                        + " uncompressed bytes; rewrote " + jsonFilesRewritten + " JSON file(s)."
                        + (protectedDuplicates.isEmpty() ? "" : " Retained "
                        + protectedDuplicates.size() + " conservatively referenced alias(es)."));
            }
        } catch (IOException exception) {
            if (logger != null) {
                logger.warn("[BedrockPackOptimizer] Exact-texture consolidation skipped: "
                        + exception.getMessage());
            }
        }
    }

    private static boolean isCancelled(BooleanSupplier cancellationRequested) {
        return Cancellation.isCancelled(cancellationRequested);
    }

    private static boolean inspectAndRewriteJson(Path file,
                                                 Map<String, Replacement> replacements,
                                                 AliasMatcher aliasMatcher,
                                                 Set<Path> protectedDuplicates) throws IOException {
        String original = Files.readString(file, StandardCharsets.UTF_8);
        if (original.indexOf('\\') < 0 && !aliasMatcher.containsMatch(original)) return false;
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(original);
        } catch (Exception malformed) {
            if (original.indexOf('\\') >= 0) {
                for (Replacement replacement : replacements.values()) {
                    protectedDuplicates.add(replacement.duplicateFile());
                }
            }
            aliasMatcher.protectMatches(original, protectedDuplicates);
            return false;
        }

        Rewrite rewrite = rewriteJson(parsed, replacements, aliasMatcher, protectedDuplicates);
        if (!rewrite.changed()) return false;
        writeJsonAtomically(file, rewrite.element());
        return true;
    }

    private static Rewrite rewriteJson(JsonElement element,
                                       Map<String, Replacement> replacements,
                                       AliasMatcher aliasMatcher,
                                       Set<Path> protectedDuplicates) {
        if (element == null || element.isJsonNull()) return new Rewrite(element, false);
        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (!primitive.isString()) return new Rewrite(element, false);
            String value = primitive.getAsString();
            Replacement exact = replacements.get(value);
            if (exact != null) return new Rewrite(new JsonPrimitive(exact.canonicalReference()), true);
            aliasMatcher.protectMatches(value, protectedDuplicates);
            return new Rewrite(element, false);
        }
        if (element.isJsonArray()) {
            JsonArray output = new JsonArray();
            boolean changed = false;
            for (JsonElement child : element.getAsJsonArray()) {
                Rewrite rewritten = rewriteJson(child, replacements, aliasMatcher, protectedDuplicates);
                output.add(rewritten.element());
                changed |= rewritten.changed();
            }
            return changed ? new Rewrite(output, true) : new Rewrite(element, false);
        }
        if (element.isJsonObject()) {
            JsonObject output = new JsonObject();
            boolean changed = false;
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                // Rewriting object keys could collapse two distinct members. Bedrock
                // texture references are values, so retain an alias if it appears in a key.
                protectAliasesInString(entry.getKey(), replacements, aliasMatcher, protectedDuplicates);
                Rewrite rewritten = rewriteJson(entry.getValue(), replacements,
                        aliasMatcher, protectedDuplicates);
                output.add(entry.getKey(), rewritten.element());
                changed |= rewritten.changed();
            }
            return changed ? new Rewrite(output, true) : new Rewrite(element, false);
        }
        return new Rewrite(element, false);
    }

    private static void protectAliasesInString(String value,
                                               Map<String, Replacement> replacements,
                                               AliasMatcher aliasMatcher,
                                               Set<Path> protectedDuplicates) {
        if (value == null) return;
        Replacement exact = replacements.get(value);
        if (exact != null) {
            protectedDuplicates.add(exact.duplicateFile());
            return;
        }
        aliasMatcher.protectMatches(value, protectedDuplicates);
    }

    /**
     * Scans a non-JSON, non-image file (sounds, text) for alias paths, reading it in chunks so a
     * large sound file is never held whole in memory. Each chunk is decoded byte for byte as
     * ISO-8859-1, as the whole file used to be, and the tail that could still begin a match
     * carries over into the next chunk, so every start position is tested exactly once against
     * the full text it would have seen. The protected set comes out the same.
     */
    private static void protectAliasesFoundInOpaqueFile(Path file,
                                                        AliasMatcher aliasMatcher,
                                                        Set<Path> protectedDuplicates) throws IOException {
        // A match starting at index s ends at most at s + longest - 1.
        int overlap = aliasMatcher.longestAlias() - 1;
        byte[] buffer = new byte[OPAQUE_SCAN_CHUNK_BYTES];
        String carry = "";
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.readNBytes(buffer, 0, buffer.length)) > 0) {
                String window = carry + new String(buffer, 0, read, StandardCharsets.ISO_8859_1);
                int limit = Math.max(0, window.length() - overlap);
                aliasMatcher.protectMatchesStartingBefore(window, limit, protectedDuplicates);
                carry = window.substring(limit);
            }
        }
        aliasMatcher.protectMatchesStartingBefore(carry, carry.length(), protectedDuplicates);
    }

    private static void writeJsonAtomically(Path target, JsonElement json) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".rspm-optimize.tmp");
        Files.writeString(temporary, GSON.toJson(json), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicMoveFailed) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean isImage(Path file) {
        return IMAGE_EXTENSIONS.contains(ExactTextureFingerprint.extension(file));
    }

    private static String stripExtension(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return dot > slash ? path.substring(0, dot) : path;
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static void removeEmptyDirectories(Path root) throws IOException {
        List<Path> directories;
        try (Stream<Path> stream = Files.walk(root)) {
            directories = stream.filter(Files::isDirectory)
                    .sorted(Comparator.reverseOrder())
                    .toList();
        }
        for (Path directory : directories) {
            if (directory.equals(root)) continue;
            try (Stream<Path> children = Files.list(directory)) {
                if (children.findAny().isEmpty()) Files.deleteIfExists(directory);
            }
        }
    }

    private record Replacement(Path duplicateFile,
                               String duplicateReference, String canonicalReference) {
        Replacement withoutExtension() {
            return new Replacement(duplicateFile,
                    stripExtension(duplicateReference), stripExtension(canonicalReference));
        }
    }

    private record Rewrite(JsonElement element, boolean changed) {
    }

    /** Linear-time multi-pattern matcher for converter-generated texture paths. */
    private static final class AliasMatcher {
        private final AliasNode root = new AliasNode();
        private int longestAlias;

        private AliasMatcher(Map<String, Replacement> replacements) {
            for (Map.Entry<String, Replacement> entry : replacements.entrySet()) {
                longestAlias = Math.max(longestAlias, entry.getKey().length());
                AliasNode node = root;
                for (int i = 0; i < entry.getKey().length(); i++) {
                    node = node.children.computeIfAbsent(entry.getKey().charAt(i), ignored -> new AliasNode());
                }
                node.replacement = entry.getValue();
            }
        }

        boolean containsMatch(String text) {
            return visitMatches(text, null, true);
        }

        void protectMatches(String text, Set<Path> protectedDuplicates) {
            if (text == null) return;
            visitMatches(text, text.length(), protectedDuplicates, false);
        }

        /** As {@link #protectMatches}, only for matches that start before {@code startLimit}. */
        void protectMatchesStartingBefore(String text, int startLimit, Set<Path> protectedDuplicates) {
            visitMatches(text, startLimit, protectedDuplicates, false);
        }

        int longestAlias() {
            return Math.max(1, longestAlias);
        }

        private boolean visitMatches(String text, Set<Path> protectedDuplicates, boolean stopAtFirst) {
            if (text == null) return false;
            return visitMatches(text, text.length(), protectedDuplicates, stopAtFirst);
        }

        private boolean visitMatches(String text, int startLimit, Set<Path> protectedDuplicates,
                                     boolean stopAtFirst) {
            int start = text.indexOf("textures/");
            boolean found = false;
            while (start >= 0 && start < startLimit) {
                AliasNode node = root;
                for (int index = start; index < text.length(); index++) {
                    node = node.children.get(text.charAt(index));
                    if (node == null) break;
                    if (node.replacement != null) {
                        found = true;
                        if (stopAtFirst) return true;
                        protectedDuplicates.add(node.replacement.duplicateFile());
                    }
                }
                start = text.indexOf("textures/", start + 1);
            }
            return found;
        }
    }

    private static final class AliasNode {
        private final Map<Character, AliasNode> children = new HashMap<>();
        private Replacement replacement;
    }
}
