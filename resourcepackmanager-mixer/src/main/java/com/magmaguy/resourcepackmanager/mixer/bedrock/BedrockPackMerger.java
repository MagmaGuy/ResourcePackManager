package com.magmaguy.resourcepackmanager.mixer.bedrock;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.magmaguy.resourcepackmanager.mixer.engine.MixerLogger;
import com.magmaguy.resourcepackmanager.mixer.engine.internal.ZipUtil;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Merges N Bedrock resource pack zips (one per backend in a network) into a single
 * Bedrock pack zip that the proxy can hand to Geyser. Each backend has already run
 * {@code BedrockConversion} locally and produced its own
 * {@code ResourcePackManager_Bedrock.zip}; this merger does a file-level union across
 * all of them, JSON-merges {@code textures/item_texture.json}, and regenerates a fresh
 * {@code manifest.json} pinned to a stable proxy-side UUID so Bedrock clients cache the
 * merged pack across re-merges.
 *
 * <p>Conflict policy on plain-file path collisions is <b>last writer wins</b>, mirroring
 * the existing Java-side mixer's higher-priority-pack-wins behaviour. Backends are
 * processed in the order supplied to {@link #merge(List, File, UUID)}, so callers
 * control priority by ordering the input list.
 *
 * <p>The manifest is always regenerated &mdash; never copied from an input &mdash; so the
 * merged pack always carries the proxy's stable UUID instead of any individual backend's
 * per-instance UUID. This matches the documented "Edge case: one input" requirement.
 *
 * <p>Pure JDK + Gson. No Bukkit / Velocity / Geyser API.
 */
public final class BedrockPackMerger {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String MANIFEST_NAME = "manifest.json";
    private static final String ITEM_TEXTURE_REL = "textures/item_texture.json";
    private static final String BEDROCK_PACK_NAME = "ResourcePackManager_Bedrock";

    /**
     * Top-level directories in a Bedrock pack that carry actual convertible content.
     * If a pack has none of these directories, we treat it as empty (manifest-only)
     * and skip it from the merge. Used both as a per-input filter and as a global
     * "all inputs are empty -> emit nothing" trigger.
     */
    private static final String[] REAL_CONTENT_DIRS = {
            "attachables", "animations", "geometries", "models",
            "textures/items", "textures/entity", "render_controllers", "particles",
            "sounds", "ui"
    };

    private final MixerLogger logger;

    public BedrockPackMerger(MixerLogger logger) {
        this.logger = logger;
    }

    /**
     * Merge N Bedrock pack zips into one.
     *
     * @param inputZips             the per-backend Bedrock pack zips, ordered by priority
     *                              (later entries win on plain-file collisions)
     * @param outputZip             where to write the merged zip
     * @param stableMergedPackUuid  the proxy-side stable UUID for the merged pack's
     *                              {@code header.uuid} (so Bedrock clients cache it
     *                              across re-merges). The module UUID is derived
     *                              deterministically from this.
     * @return the merged zip's File on success, {@code null} on failure
     */
    public File merge(List<File> inputZips, File outputZip, UUID stableMergedPackUuid) {
        if (outputZip == null) {
            logger.warn("[BedrockPackMerger] outputZip is null; aborting merge.");
            return null;
        }
        if (stableMergedPackUuid == null) {
            logger.warn("[BedrockPackMerger] stableMergedPackUuid is null; aborting merge.");
            return null;
        }

        List<File> inputs = inputZips == null ? Collections.emptyList() : inputZips;

        // 1. Allocate scratch directories. We unzip each input under a per-index folder
        //    next to the output zip so cleanup is straightforward and we never clobber
        //    the merged staging dir.
        File parent = outputZip.getParentFile();
        if (parent == null) parent = new File(".");
        if (!parent.exists() && !parent.mkdirs()) {
            logger.warn("[BedrockPackMerger] Failed to create output directory: " + parent.getAbsolutePath());
            return null;
        }

        File scratchRoot = new File(parent, "_bedrock_merge_scratch_" + Long.toHexString(System.nanoTime()));
        File stagingDir = new File(scratchRoot, "merged");

        try {
            if (scratchRoot.exists()) recursivelyDelete(scratchRoot);
            if (!stagingDir.mkdirs()) {
                logger.warn("[BedrockPackMerger] Failed to create staging dir: " + stagingDir.getAbsolutePath());
                return null;
            }

            // Zero-inputs path: no content at all — emit nothing and delete any stale
            // output. Per user policy, we don't fall back to a manifest-only placeholder.
            if (inputs.isEmpty()) {
                logger.info("[BedrockPackMerger] No input Bedrock packs supplied; producing no merged pack.");
                deleteOutputIfExists(outputZip);
                return null;
            }

            // 2. Unzip each input under scratch/in_<i>/.
            List<File> unzippedDirs = new ArrayList<>(inputs.size());
            for (int i = 0; i < inputs.size(); i++) {
                File zip = inputs.get(i);
                if (zip == null || !zip.isFile()) {
                    logger.warn("[BedrockPackMerger] Input #" + i + " is missing or not a file; skipping: "
                            + (zip == null ? "null" : zip.getAbsolutePath()));
                    unzippedDirs.add(null);
                    continue;
                }
                File dest = new File(scratchRoot, "in_" + i);
                if (!dest.mkdirs()) {
                    logger.warn("[BedrockPackMerger] Failed to create unzip dir for input #" + i + ": "
                            + dest.getAbsolutePath());
                    unzippedDirs.add(null);
                    continue;
                }
                try {
                    ZipUtil.unzip(zip, dest);
                    unzippedDirs.add(dest);
                } catch (IOException e) {
                    logger.warn("[BedrockPackMerger] Failed to unzip input #" + i + " ("
                            + zip.getAbsolutePath() + "): " + e.getMessage());
                    unzippedDirs.add(null);
                }
            }

            // 2b. Empty-input detection: if every input is a manifest-only / no-real-
            // content pack, emit nothing and delete any stale output. Per user policy
            // we don't fall back to a placeholder; the Bedrock binder will just not
            // register an RSPM pack for new sessions.
            boolean anyHasContent = false;
            for (File packDir : unzippedDirs) {
                if (packDir == null) continue;
                if (hasRealContent(packDir)) {
                    anyHasContent = true;
                    break;
                }
            }
            if (!anyHasContent) {
                logger.info("[BedrockPackMerger] Every input pack is empty (manifest-only); producing no merged pack.");
                deleteOutputIfExists(outputZip);
                return null;
            }

            // 3. Walk every file in each unzipped pack except manifest.json (always
            //    regenerated below) and textures/item_texture.json (JSON-merged below).
            //    File-level union, last writer wins on collision. Track collision owner
            //    indices so the warn message names the conflicting backends.
            Map<String, Integer> lastWriterByRelPath = new LinkedHashMap<>();
            int[] minEngineVersion = null;

            for (int i = 0; i < unzippedDirs.size(); i++) {
                File packDir = unzippedDirs.get(i);
                if (packDir == null) continue;

                // Capture min_engine_version from this pack's manifest before we drop it.
                int[] mev = readMinEngineVersion(new File(packDir, MANIFEST_NAME));
                if (mev != null) {
                    if (minEngineVersion == null || compareVersionTriplets(mev, minEngineVersion) > 0) {
                        minEngineVersion = mev;
                    }
                }

                int backendIndex = i;
                Path packRoot = packDir.toPath();
                try {
                    Files.walkFileTree(packRoot, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                            String rel = packRoot.relativize(file).toString().replace('\\', '/');
                            if (rel.equals(MANIFEST_NAME)) return FileVisitResult.CONTINUE;
                            if (rel.equals(ITEM_TEXTURE_REL)) return FileVisitResult.CONTINUE;

                            File target = new File(stagingDir, rel);
                            if (target.exists()) {
                                Integer prev = lastWriterByRelPath.get(rel);
                                logger.warn("[BedrockPackMerger] File collision on '" + rel
                                        + "' between backend #" + (prev == null ? "?" : prev)
                                        + " and backend #" + backendIndex
                                        + "; last writer wins (backend #" + backendIndex + ").");
                            }
                            Files.createDirectories(target.getParentFile().toPath());
                            Files.copy(file, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                            lastWriterByRelPath.put(rel, backendIndex);
                            return FileVisitResult.CONTINUE;
                        }
                    });
                } catch (IOException e) {
                    logger.warn("[BedrockPackMerger] Walk failed on backend #" + backendIndex
                            + " (" + packDir.getAbsolutePath() + "): " + e.getMessage());
                }
            }

            // 4. JSON-merge every textures/item_texture.json: union the texture_data map.
            mergeItemTextures(unzippedDirs, stagingDir);

            // 5. Generate a fresh manifest.json pinned to stableMergedPackUuid.
            // Version is derived from staged content so no-op proxy remixes keep
            // Bedrock's (uuid, version) cache key stable across restarts.
            int[] mev = minEngineVersion != null ? minEngineVersion : new int[]{1, 21, 0};
            String cacheBustToken = contentDigest(stagingDir, mev);
            writeMergedManifest(stagingDir, stableMergedPackUuid, mev, cacheBustToken);

            // 6. Zip staging dir into outputZip.
            return zipStagingDir(stagingDir, outputZip);
        } catch (Exception e) {
            logger.warn("[BedrockPackMerger] Merge failed: " + e.getMessage());
            return null;
        } finally {
            // Best-effort cleanup of the scratch root.
            try {
                recursivelyDelete(scratchRoot);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * JSON-merge each input's {@code textures/item_texture.json}: union the
     * {@code texture_data} map. Canonical {@code resource_pack_name} / {@code texture_name}
     * fields are kept from the merged-pack convention so the output matches what
     * Geyser expects on the proxy side.
     */
    private void mergeItemTextures(List<File> unzippedDirs, File stagingDir) {
        JsonObject mergedTextureData = new JsonObject();
        Map<String, Integer> ownerByKey = new LinkedHashMap<>();

        for (int i = 0; i < unzippedDirs.size(); i++) {
            File packDir = unzippedDirs.get(i);
            if (packDir == null) continue;
            File f = new File(packDir, ITEM_TEXTURE_REL);
            if (!f.isFile()) continue;
            JsonObject root = parseJsonOrNull(f);
            if (root == null) {
                logger.warn("[BedrockPackMerger] Could not parse " + ITEM_TEXTURE_REL
                        + " from backend #" + i + " (" + f.getAbsolutePath() + ")");
                continue;
            }
            if (!root.has("texture_data") || !root.get("texture_data").isJsonObject()) continue;
            JsonObject textureData = root.getAsJsonObject("texture_data");
            for (String key : textureData.keySet()) {
                if (mergedTextureData.has(key)) {
                    Integer prev = ownerByKey.get(key);
                    logger.warn("[BedrockPackMerger] item_texture.json key collision on '" + key
                            + "' between backend #" + (prev == null ? "?" : prev)
                            + " and backend #" + i
                            + "; last writer wins (backend #" + i + ").");
                }
                mergedTextureData.add(key, textureData.get(key));
                ownerByKey.put(key, i);
            }
        }

        JsonObject root = new JsonObject();
        root.addProperty("resource_pack_name", BEDROCK_PACK_NAME);
        root.addProperty("texture_name", "atlas.items");
        root.add("texture_data", mergedTextureData);

        File output = new File(stagingDir, ITEM_TEXTURE_REL);
        try {
            Files.createDirectories(output.getParentFile().toPath());
            try (FileWriter w = new FileWriter(output, StandardCharsets.UTF_8)) {
                GSON.toJson(root, w);
            }
        } catch (IOException e) {
            logger.warn("[BedrockPackMerger] Failed to write merged item_texture.json: " + e.getMessage());
        }
    }

    /**
     * Read {@code header.min_engine_version} from a backend's manifest, returning
     * {@code null} if absent or malformed. The merged pack uses the highest min_engine_version
     * seen across inputs so the merged pack still loads on every client capable of any input.
     */
    private int[] readMinEngineVersion(File manifestFile) {
        if (!manifestFile.isFile()) return null;
        JsonObject root = parseJsonOrNull(manifestFile);
        if (root == null) return null;
        if (!root.has("header") || !root.get("header").isJsonObject()) return null;
        JsonObject header = root.getAsJsonObject("header");
        if (!header.has("min_engine_version")) return null;
        JsonElement el = header.get("min_engine_version");
        if (!el.isJsonArray()) return null;
        JsonArray arr = el.getAsJsonArray();
        if (arr.size() < 3) return null;
        try {
            return new int[]{
                    arr.get(0).getAsInt(),
                    arr.get(1).getAsInt(),
                    arr.get(2).getAsInt()
            };
        } catch (Exception e) {
            return null;
        }
    }

    private int compareVersionTriplets(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            int cmp = Integer.compare(a[i], b[i]);
            if (cmp != 0) return cmp;
        }
        return 0;
    }

    /**
     * Generate a fresh merged manifest pinned to {@code stableMergedPackUuid}. The
     * version triplet comes from staged content so Bedrock only sees a new pack
     * version when the merged bytes actually change.
     */
    private void writeMergedManifest(File outputDir, UUID stableMergedPackUuid,
                                     int[] minEngineVersion, String cacheBustToken) {
        UUID moduleUuid = UUID.nameUUIDFromBytes(
                (stableMergedPackUuid.toString() + ":resources").getBytes(StandardCharsets.UTF_8));

        int[] versionTriplet = deriveVersionTriplet(cacheBustToken);

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format_version", 2);

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("name", "ResourcePackManager Network-Merged Bedrock Pack");
        header.put("description", "Merged across N backends by the ResourcePackManager proxy plugin");
        header.put("uuid", stableMergedPackUuid.toString());
        header.put("version", Arrays.asList(versionTriplet[0], versionTriplet[1], versionTriplet[2]));
        header.put("min_engine_version",
                Arrays.asList(minEngineVersion[0], minEngineVersion[1], minEngineVersion[2]));
        manifest.put("header", header);

        Map<String, Object> module = new LinkedHashMap<>();
        module.put("type", "resources");
        module.put("name", "ResourcePackManager Network Resources");
        module.put("description", "Merged Bedrock resources from network backends");
        module.put("uuid", moduleUuid.toString());
        module.put("version", Arrays.asList(versionTriplet[0], versionTriplet[1], versionTriplet[2]));
        manifest.put("modules", Collections.singletonList(module));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("authors", Collections.singletonList("ResourcePackManager"));
        Map<String, Object> generatedWith = new LinkedHashMap<>();
        generatedWith.put("ResourcePackManager", Collections.singletonList("proxy-merged"));
        metadata.put("generated_with", generatedWith);
        manifest.put("metadata", metadata);

        File manifestFile = new File(outputDir, MANIFEST_NAME);
        try {
            Files.createDirectories(outputDir.toPath());
            try (FileWriter w = new FileWriter(manifestFile, StandardCharsets.UTF_8)) {
                GSON.toJson(manifest, w);
            }
        } catch (IOException e) {
            logger.warn("[BedrockPackMerger] Failed to write merged manifest.json: " + e.getMessage());
        }
    }

    /**
     * Same derivation as {@code BedrockManifest.deriveVersionTriplet} so the merged pack
     * bumps its cache key when merged content changes in the same way each backend's
     * local pack does.
     */
    private static int[] deriveVersionTriplet(String cacheBustToken) {
        long seed;
        if (cacheBustToken == null || cacheBustToken.isEmpty()) {
            seed = System.currentTimeMillis();
        } else {
            try {
                seed = Long.parseLong(cacheBustToken);
            } catch (NumberFormatException nfe) {
                String hex = cacheBustToken.replaceAll("[^0-9A-Fa-f]", "");
                if (hex.length() >= 15) {
                    try {
                        seed = Long.parseUnsignedLong(hex.substring(0, 15), 16);
                    } catch (NumberFormatException ignored) {
                        seed = cacheBustToken.hashCode() & 0xffffffffL;
                    }
                } else {
                    seed = cacheBustToken.hashCode() & 0xffffffffL;
                }
            }
        }
        long abs = seed < 0 ? -seed : seed;
        int patch = (int) (abs % 1000);
        int minor = (int) ((abs / 1000) % 1000);
        int major = (int) ((abs / 1_000_000) % 1000);
        if (major == 0) major = 1;
        return new int[]{major, minor, patch};
    }

    private String contentDigest(File stagingDir, int[] minEngineVersion) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            Path root = stagingDir.toPath();
            try (Stream<Path> stream = Files.walk(root)) {
                List<Path> files = stream
                        .filter(Files::isRegularFile)
                        .sorted(Comparator.comparing(path -> root.relativize(path).toString().replace('\\', '/')))
                        .toList();
                for (Path file : files) {
                    String relative = root.relativize(file).toString().replace('\\', '/');
                    digest.update(relative.getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    digest.update(Files.readAllBytes(file));
                    digest.update((byte) 0);
                }
            }
            digest.update("min_engine_version".getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            for (int component : minEngineVersion) {
                digest.update(Integer.toString(component).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            logger.warn("[BedrockPackMerger] Failed to derive content digest; falling back to timestamp cache bust: "
                    + e.getMessage());
            return String.valueOf(System.currentTimeMillis());
        }
    }

    /**
     * Heuristic for "is this unzipped Bedrock pack actually carrying content, or
     * is it just a manifest-only shell?" Returns true iff any of the well-known
     * Bedrock content directories exists and is non-empty under the pack root.
     * Used to decide whether to skip the merge entirely (when every input is a
     * shell). Conservative — false negatives (calling a real pack empty) would
     * silently drop content, so we err toward marking packs as having content
     * whenever in doubt.
     */
    private static boolean hasRealContent(File packDir) {
        if (packDir == null || !packDir.isDirectory()) return false;
        for (String rel : REAL_CONTENT_DIRS) {
            File dir = new File(packDir, rel);
            if (dir.isDirectory()) {
                File[] children = dir.listFiles();
                if (children != null && children.length > 0) return true;
            }
        }
        return false;
    }

    /**
     * Delete the output zip if it exists from a previous successful merge so the
     * "no content this cycle" state is reflected on disk — the proxy's
     * {@code GeyserBinder} will not register a stale pack on the next session.
     */
    private void deleteOutputIfExists(File outputZip) {
        if (outputZip == null) return;
        try {
            if (Files.deleteIfExists(outputZip.toPath())) {
                logger.info("[BedrockPackMerger] Deleted previous merged pack: " + outputZip.getAbsolutePath());
            }
        } catch (IOException e) {
            logger.warn("[BedrockPackMerger] Failed to delete previous merged pack "
                    + outputZip.getAbsolutePath() + ": " + e.getMessage());
        }
    }

    /**
     * Atomic temp-then-rename zip write, matching {@code BedrockZip.zip}'s convention.
     * Reader (Geyser, proxy HTTP) must never see a half-written zip — writes go to
     * {@code <output>.tmp}, atomic-move to {@code <output>} on success.
     */
    private File zipStagingDir(File stagingDir, File outputZip) {
        File parent = outputZip.getParentFile();
        if (parent == null) parent = new File(".");
        if (!parent.exists()) parent.mkdirs();

        File tmpFile = new File(parent, outputZip.getName() + ".tmp");
        Path sourcePath = stagingDir.toPath();

        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmpFile)));
             Stream<Path> stream = Files.walk(sourcePath)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> sourcePath.relativize(path).toString().replace('\\', '/')))
                    .toList();
            for (Path file : files) {
                String entryName = sourcePath.relativize(file).toString().replace('\\', '/');
                ZipEntry entry = new ZipEntry(entryName);
                entry.setTime(0L);
                zos.putNextEntry(entry);
                Files.copy(file, zos);
                zos.closeEntry();
            }
        } catch (IOException e) {
            logger.warn("[BedrockPackMerger] Failed to zip merged Bedrock pack: " + e.getMessage());
            try {
                Files.deleteIfExists(tmpFile.toPath());
            } catch (IOException ignored) {
            }
            return null;
        }

        try {
            if (outputZip.isFile() && Files.mismatch(tmpFile.toPath(), outputZip.toPath()) == -1L) {
                Files.deleteIfExists(tmpFile.toPath());
                return outputZip;
            }
            Files.move(tmpFile.toPath(), outputZip.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicMoveFailed) {
            try {
                Files.move(tmpFile.toPath(), outputZip.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e2) {
                logger.warn("[BedrockPackMerger] Failed to publish merged Bedrock pack: " + e2.getMessage());
                try {
                    Files.deleteIfExists(tmpFile.toPath());
                } catch (IOException ignored) {
                }
                return null;
            }
        }

        return outputZip;
    }

    private JsonObject parseJsonOrNull(File f) {
        try (FileReader r = new FileReader(f, StandardCharsets.UTF_8)) {
            JsonElement el = JsonParser.parseReader(r);
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void recursivelyDelete(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) recursivelyDelete(child);
            }
        }
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException ignored) {
        }
    }
}
