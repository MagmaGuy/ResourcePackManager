package com.magmaguy.resourcepackmanager.bedrock;

import com.google.gson.JsonObject;
import com.magmaguy.resourcepackmanager.bedrock.converter.*;
import com.magmaguy.resourcepackmanager.bedrock.generic.AssetResolver;
import com.magmaguy.resourcepackmanager.bedrock.generic.BaseItemResolver;
import com.magmaguy.resourcepackmanager.bedrock.generic.GenericGeyserMappingBuilder;
import com.magmaguy.resourcepackmanager.bedrock.generic.GenericJavaScanner;
import com.magmaguy.resourcepackmanager.bedrock.generic.GeyserDefinitionEntry;
import com.magmaguy.resourcepackmanager.bedrock.generic.ItemModelTreeWalker;
import com.magmaguy.resourcepackmanager.bedrock.generic.ItemsDefinition;
import com.magmaguy.resourcepackmanager.bedrock.generic.MappedItemRegistry;
import com.magmaguy.resourcepackmanager.bedrock.generic.ResolvedLeaf;
import com.magmaguy.resourcepackmanager.bedrock.generic.ResolvedModel;
import com.magmaguy.resourcepackmanager.bedrock.model.BedrockManifest;
import com.magmaguy.resourcepackmanager.bedrock.util.BedrockShortName;
import com.magmaguy.resourcepackmanager.bedrock.util.BedrockZip;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Main orchestrator for producing a Bedrock resource pack and Geyser custom mappings
 * file from the merged Java pack. Namespace-agnostic: every items definition in the
 * merged pack (FreeMinecraftModels, ModelEngine, EliteMobs, any other plugin) flows
 * through the same generic pipeline. Gated on the {@link BedrockConverterContext}'s
 * {@link BedrockConverterContext#isBedrockTargetPresent()} — without a Bedrock target,
 * the whole pipeline is pure overhead and is skipped.
 *
 * <p>Pipeline: run generic items pipeline → emit item_texture / manifest /
 * mappings → zip → deploy to Geyser.</p>
 *
 * <p>This class is platform-neutral. Used by both the backend Bukkit plugin (after
 * Mix.java mixes the per-plugin Java packs) and the proxy plugin (after the proxy's
 * NetworkSync mixes the per-backend Java packs). Platform-specific concerns (where
 * to find Geyser, whether conversion is enabled, plugin version string) come in via
 * the {@link BedrockConverterContext}.</p>
 */
public class BedrockConversion {

    public static final String BEDROCK_PACK_NAME = "ResourcePackManager_Bedrock";
    public static final String GEYSER_MAPPINGS_NAME = "rspm_geyser_mappings.json";

    /**
     * Called early during startup (before Geyser scans its {@code custom_mappings/}
     * folder) to copy the previous run's Geyser mappings into place so that Geyser
     * registers our custom-item identifiers at its own boot. The Bedrock pack ZIP is
     * NOT pre-deployed here — it's served live per-session by the platform's pack
     * provider, so a stale pre-deploy can't poison Geyser's in-memory pack cache
     * for the rest of the session.
     * <p>
     * Mappings still need this pre-boot copy because Geyser's
     * {@code GeyserDefineCustomItemsEvent} is a lifecycle event that fires exactly
     * once at startup — there is no per-session mapping refresh, so changes to the
     * custom-item SET require a server restart to take effect.
     */
    public static void deployPreviousMappingsIfNeeded(BedrockConverterContext ctx) {
        if (ctx == null) return;
        if (!ctx.isBedrockConversionEnabled()) return;

        File previousMappings = ctx.previousMappingsFile();
        if (previousMappings == null || !previousMappings.exists()) return;

        // Install both the platform logger sink AND the debug toggle for the
        // boot-time pre-deploy path. Without the sink install, any
        // BedrockLog.debug() in GeyserDeployer would route through the no-op
        // default sink and never print even when debug was opted into. Reset
        // the sink afterwards so the next BedrockConversion.generate() can
        // install its own (which it would do anyway, but cleanliness matters
        // for the single-helper standalone-test case BedrockLog was designed
        // around).
        BedrockLog.set(ctx.logger());
        BedrockLog.setDebug(ctx.isBedrockConverterDebug());
        try {
            ctx.deployMappingsIfNeeded(previousMappings);
        } finally {
            BedrockLog.set(null);
        }
    }

    /**
     * Main entry point. Called from Mix.java (backend) or NetworkSync (proxy) after
     * the Java pack is zipped but before the unzipped folder is cleaned up.
     *
     * @param mergedJavaPack the unzipped merged Java resource pack directory
     * @param outputDir      the output directory where the Bedrock zip and mappings
     *                       JSON will be written
     * @param ctx            platform-specific context (logger, version, target
     *                       detection, deploy hook)
     */
    public static void generate(File mergedJavaPack, File outputDir, BedrockConverterContext ctx) {
        BedrockLog.set(ctx.logger());
        BedrockLog.setDebug(ctx.isBedrockConverterDebug());
        BedrockDisplayOffsets.set(ctx.displayOffsets());
        try {
            if (!ctx.isBedrockConversionEnabled()) return;
            if (!ctx.isBedrockTargetPresent()) return;

            // Per-mix "Starting Bedrock resource pack conversion..." fires every
            // /reload and every mix cycle; demoted to debug so a clean run only
            // emits the single "Bedrock conversion complete: N mappings" summary.
            BedrockLog.debug("Starting Bedrock resource pack conversion for GeyserMC...");

            // Create the Bedrock pack staging directory and copy the pack icon.
            File bedrockDir = new File(outputDir, BEDROCK_PACK_NAME);
            if (bedrockDir.exists()) recursivelyDelete(bedrockDir);
            bedrockDir.mkdirs();
            copyPackIcon(mergedJavaPack, bedrockDir);

            // Single, namespace-agnostic conversion pipeline. The generic scanner walks
            // every items definition under assets/<ns>/items/** (including the
            // freeminecraftmodels namespace — there is no longer a FMM-specific path).
            // Icons are accumulated into iconTextureMap as a side-effect.
            Map<String, String> iconTextureMap = new LinkedHashMap<>();
            MappedItemRegistry registry = new MappedItemRegistry();
            runGenericPipeline(mergedJavaPack, bedrockDir, iconTextureMap, registry);

            // No convertible content (no custom items / models in the merged Java pack).
            // Per user policy: do NOT fall back to a manifest-only minimal pack. Either
            // we have real content or we emit nothing. This avoids a useless 22KB pack
            // with a prompt when the backend has nothing to ship. Also nuke any stale
            // output from the previous run so the backend's /bedrock.zip route 404s
            // cleanly instead of serving last cycle's content forever.
            if (registry.totalMappings() == 0) {
                // No convertible content this cycle. Clear out previous-run output
                // so the backend's /bedrock.zip route 404s cleanly instead of
                // serving last cycle's content forever. Silent — operator doesn't
                // need to know about routine cleanup.
                recursivelyDelete(bedrockDir);
                File staleZip = new File(outputDir, BEDROCK_PACK_NAME + ".zip");
                File staleMappings = new File(outputDir, GEYSER_MAPPINGS_NAME);
                if (staleZip.exists()) {
                    try {
                        Files.deleteIfExists(staleZip.toPath());
                    } catch (IOException e) {
                        BedrockLog.warn("Failed to delete stale Bedrock pack zip: " + e.getMessage());
                    }
                }
                if (staleMappings.exists()) {
                    try {
                        Files.deleteIfExists(staleMappings.toPath());
                    } catch (IOException e) {
                        BedrockLog.warn("Failed to delete stale Geyser mappings: " + e.getMessage());
                    }
                }
                return;
            }

            // 4. Generate item_texture.json (required by Geyser for icon resolution).
            generateItemTexture(iconTextureMap, bedrockDir);

            // 5. Generate manifest. The cache-bust token is just the build timestamp —
            // enough to bump the manifest version triplet per build so Bedrock invalidates
            // its (uuid, version)-keyed pack cache. Not a hash of pack contents, so
            // rebuilding identical contents still produces a new version (i.e. no
            // reproducible-build property).
            String cacheBustToken = String.valueOf(System.currentTimeMillis());
            String pluginVersion = ctx.pluginVersion();
            BedrockManifest.write(bedrockDir, pluginVersion, cacheBustToken);

            // 6. Generate merged Geyser mappings.
            File mappingsFile = new File(outputDir, GEYSER_MAPPINGS_NAME);
            GenericGeyserMappingBuilder.merge(registry, mappingsFile);

            // 7. Zip
            File bedrockZip = BedrockZip.zip(bedrockDir, outputDir, BEDROCK_PACK_NAME);
            if (bedrockZip == null) {
                BedrockLog.warn("Failed to zip Bedrock resource pack!");
                return;
            }
            recursivelyDelete(bedrockDir);

            // 8. Deploy mappings to Geyser. The hook is platform-specific:
            //    - Backend: copies into plugins/Geyser-Spigot/custom_mappings/
            //    - Proxy:   copies into plugins/Geyser-Velocity|Geyser-BungeeCord/custom_mappings/
            //    Either way it lands on disk for NEXT boot, since Geyser's custom-item
            //    registry is boot-frozen.
            ctx.deployMappingsIfNeeded(mappingsFile);

            BedrockLog.info("Bedrock conversion complete: " + registry.totalMappings() + " mappings ("
                    + registry.uniqueModelsWritten() + " unique models).");

        } catch (Exception e) {
            BedrockLog.warn("Bedrock conversion failed: " + e.getMessage());
            e.printStackTrace();
        } finally {
            BedrockLog.set(null);
            BedrockDisplayOffsets.set(null);
        }
    }

    /**
     * Generic pipeline entry point: scans non-FMM items definitions, walks each model tree,
     * and for every leaf either (a) copies the layer0 PNG and registers a flat-icon entry,
     * or (b) emits Bedrock geometry/attachable/animation files via the FMM helpers and
     * registers a 3D entry. The supplied {@link MappedItemRegistry} accumulates entries
     * across both branches; the caller reads it back to serialise the merged Geyser
     * mappings file in Phase 7.
     *
     * <p>The {@code iconTextureMap} parameter is mutated in-place: each emitted icon adds
     * one {@code (safeId -> textures/items/<safeId>)} entry so the subsequent
     * {@link #generateItemTexture} call writes a unified atlas including both FMM and
     * generic icons.</p>
     */
    private static void runGenericPipeline(File mergedJavaPack, File bedrockDir,
                                           Map<String, String> iconTextureMap,
                                           MappedItemRegistry registry) {
        try {
            List<ItemsDefinition> generic = GenericJavaScanner.scan(mergedJavaPack);
            if (generic.isEmpty()) return;

            AssetResolver assetResolver = new AssetResolver(mergedJavaPack);
            int flatEmitted = 0;
            int threeEmitted = 0;
            // Phase G: cache of per-model assets (stitch + geometry id + animation triple)
            // keyed by Java model ref. Populated on first encounter of a model, reused for
            // every (model × base item) attachable emission.
            Map<String, SharedModelAssets> modelAssetsCache = new LinkedHashMap<>();

            for (ItemsDefinition def : generic) {
                List<ResolvedLeaf> leaves = ItemModelTreeWalker.walk(def, assetResolver);
                List<String> baseItems = BaseItemResolver.resolve(def, assetResolver);

                for (ResolvedLeaf leaf : leaves) {
                    Optional<ResolvedModel> modelOpt = assetResolver.resolveModel(leaf.modelRef());
                    // Unresolved models are silently skipped — aggregate count is
                    // visible in the final "Bedrock conversion complete: X mappings"
                    // summary. Per-item warns spam the console without diagnostic value.
                    if (modelOpt.isEmpty()) continue;
                    ResolvedModel resolved = modelOpt.get();

                    // Split namespace and path from the model reference (e.g.
                    // "elitemobs:gear/bronze_sword" → ns="elitemobs", path="gear/bronze_sword").
                    int colon = leaf.modelRef().indexOf(':');
                    String namespace = colon > 0 ? leaf.modelRef().substring(0, colon) : "minecraft";
                    String rawPath = colon > 0 ? leaf.modelRef().substring(colon + 1) : leaf.modelRef();

                    // Short, opaque, deterministic stems. Previously we concatenated
                    // the full Java namespace + path + base item into file paths and
                    // identifiers; this routinely produced 100+ char file paths inside
                    // the Bedrock pack and triggered Geyser's "exceeds 80 characters"
                    // warning hundreds of times per merge. See BedrockShortName javadoc.
                    String modelHash = BedrockShortName.forModel(leaf.modelRef());
                    String iconKey = modelHash;

                    if (resolved.isFlatBuiltin()) {
                        boolean firstTime = registry.registerModelOnce(leaf.modelRef());
                        if (firstTime) {
                            if (!emitFlatIcon(resolved, iconKey, mergedJavaPack, bedrockDir, iconTextureMap)) {
                                continue;
                            }
                        }
                        String itemsStem = def.itemsRelPath();
                        String javaItemModel = def.itemIdentifier();
                        for (String base : baseItems) {
                            String mappingHash = BedrockShortName.forBaseMapping(leaf.modelRef(), base);
                            String tierBedrockId = BedrockShortName.bedrockIdentifier(mappingHash);
                            registry.addMapping(base, new GeyserDefinitionEntry(
                                    tierBedrockId,
                                    javaItemModel,
                                    leaf.predicates(),
                                    iconKey,
                                    resolved.isHandheldVariant()
                            ));
                            String attachableOut = mappingHash;
                            EquipmentAttachableGenerator.tryEnrichWithArmorAttachable(
                                    tierBedrockId, attachableOut, namespace, itemsStem,
                                    base, assetResolver, mergedJavaPack, bedrockDir);
                        }
                        flatEmitted++;
                    } else {
                        if (!emitGenericThreeD(leaf, resolved, modelHash, iconKey,
                                def.itemIdentifier(),
                                baseItems, registry, modelAssetsCache,
                                mergedJavaPack, bedrockDir, iconTextureMap)) {
                            continue;
                        }
                        threeEmitted++;
                    }
                }
            }

            // Per-pipeline emission counts are folded into the final
            // "Bedrock conversion complete" summary in generate() — no need
            // to also log them here.
        } catch (Exception e) {
            BedrockLog.warn("[BedrockConverter] Generic pipeline failed: " + e.getMessage());
        }
    }

    private static boolean emitGenericThreeD(ResolvedLeaf leaf,
                                             ResolvedModel resolved,
                                             String modelHash,
                                             String iconKey,
                                             String javaItemModel,
                                             List<String> baseItems,
                                             MappedItemRegistry registry,
                                             Map<String, SharedModelAssets> modelAssetsCache,
                                             File mergedJavaPack,
                                             File bedrockDir,
                                             Map<String, String> iconTextureMap) {
        // modelHash is the short, opaque, deterministic stem (see BedrockShortName)
        // used for every per-model file path in the Bedrock pack — texture atlas,
        // geometry, animation, and the iconKey under which the per-model rendered
        // inventory icon is registered with Geyser. Keeping a single stem across
        // all four file types means a future diff against the pack ZIP is trivial:
        // grep <modelHash> finds every file produced for that source model.

        SharedModelAssets shared;
        if (registry.registerModelOnce(leaf.modelRef())) {
            // Pass the short stem as both modelName and boneName to the stitcher.
            // Internally the stitcher concatenates them with "__" / "/" for the
            // per-bone icon and atlas file paths; passing the same short hash on
            // both sides yields <hash>__<hash>.png (≈25 chars) — short enough
            // to stay well under Bedrock's 80-char file-path warning threshold.
            // The original Java model ref is still preserved in modelAssetsCache
            // for cross-base-item asset reuse, and BedrockLog messages here include
            // leaf.modelRef() so the hash never becomes opaque during debugging.
            TextureStitcher.StitchResult stitch = TextureStitcher.stitchSingleModel(
                    modelHash, modelHash, resolved.mergedJson(),
                    mergedJavaPack, bedrockDir);
            if (stitch == null) {
                BedrockLog.warn("[BedrockConverter] Failed to stitch textures for generic 3D model "
                        + leaf.modelRef() + "; skipping");
                return false;
            }

            String geometryIdentifier = "geometry." + BedrockShortName.BEDROCK_NAMESPACE + "." + modelHash;
            String geometryOutputPath = modelHash;
            String resultGeoId = FmmGeometryConverter.convertWithIdentifier(
                    geometryIdentifier, geometryOutputPath, resolved.mergedJson(),
                    stitch.spriteMap(), stitch.atlasWidth(), stitch.atlasHeight(),
                    bedrockDir);
            if (resultGeoId == null) {
                BedrockLog.warn("[BedrockConverter] Failed to convert geometry for generic 3D model "
                        + leaf.modelRef() + "; skipping");
                return false;
            }

            String animBaseId = BedrockShortName.BEDROCK_NAMESPACE + "." + modelHash;
            String animFileBase = modelHash;
            FmmAnimationGenerator.AnimationIds animIds = FmmAttachableGenerator.prepareAnimations(
                    animBaseId, animFileBase, resolved.mergedJson(), bedrockDir);
            if (animIds == null) {
                BedrockLog.warn("[BedrockConverter] Failed to prepare animations for generic 3D model "
                        + leaf.modelRef() + "; skipping");
                return false;
            }

            String iconRel = "textures/items/" + iconKey;
            File iconFile = new File(bedrockDir, iconRel + ".png");
            boolean rendered = IconRenderer.renderIcon(resolved.mergedJson(), mergedJavaPack, iconFile);
            if (!rendered) {
                BedrockLog.warn("[BedrockConverter] Icon rendering failed for " + leaf.modelRef()
                        + "; using missing-texture placeholder");
                IconRenderer.writeMissingPlaceholder(iconFile);
            }
            iconTextureMap.put(iconKey, iconRel);

            shared = new SharedModelAssets(stitch, resultGeoId, animIds);
            modelAssetsCache.put(leaf.modelRef(), shared);
        } else {
            shared = modelAssetsCache.get(leaf.modelRef());
            if (shared == null) return false;
        }

        boolean anyEmitted = false;
        for (String base : baseItems) {
            String mappingHash = BedrockShortName.forBaseMapping(leaf.modelRef(), base);
            String tierBedrockId = BedrockShortName.bedrockIdentifier(mappingHash);
            String attachableOutPath = mappingHash;

            String result = FmmAttachableGenerator.writeAttachable(
                    tierBedrockId, attachableOutPath,
                    shared.geometryId(), shared.stitch().bedrockTexturePath(),
                    shared.animIds(), bedrockDir);
            if (result == null) continue;

            registry.addMapping(base, new GeyserDefinitionEntry(
                    tierBedrockId,
                    javaItemModel,
                    leaf.predicates(),
                    iconKey,
                    resolved.isHandheldVariant()
            ));
            anyEmitted = true;
        }
        return anyEmitted;
    }

    private record SharedModelAssets(
            TextureStitcher.StitchResult stitch,
            String geometryId,
            FmmAnimationGenerator.AnimationIds animIds) {
    }

    private static boolean emitFlatIcon(ResolvedModel resolved, String safeId,
                                        File mergedJavaPack, File bedrockDir,
                                        Map<String, String> iconTextureMap) {
        JsonObject merged = resolved.mergedJson();
        if (!merged.has("textures") || !merged.get("textures").isJsonObject()) return false;
        JsonObject textures = merged.getAsJsonObject("textures");
        String layer0 = null;
        for (String key : List.of("layer0", "0", "#layer0", "#0")) {
            if (textures.has(key) && textures.get(key).isJsonPrimitive()) {
                layer0 = textures.get(key).getAsString();
                break;
            }
        }
        if (layer0 == null) {
            // Per-item structural skip — expected on any flat-builtin model that
            // doesn't expose a layer0/0 texture key. Demoted to debug.
            BedrockLog.debug("[BedrockConverter] Flat-icon model " + resolved.identifier()
                    + " has no layer0/0 texture reference; skipping");
            return false;
        }
        int colon = layer0.indexOf(':');
        String ns = colon >= 0 ? layer0.substring(0, colon) : "minecraft";
        String texPath = colon >= 0 ? layer0.substring(colon + 1) : layer0;
        File source = new File(mergedJavaPack, "assets/" + ns + "/textures/" + texPath + ".png");
        if (!source.isFile()) {
            // Per-item asset miss — typically a layer0 that points at a vanilla
            // texture not bundled in the merged pack. Demoted to debug.
            BedrockLog.debug("[BedrockConverter] Flat-icon texture not found: " + source.getPath());
            return false;
        }
        String iconRel = "textures/items/" + safeId;
        File dest = new File(bedrockDir, iconRel + ".png");
        try {
            Files.createDirectories(dest.getParentFile().toPath());
            TextureStitcher.writeIconCroppedIfFlipbook(source, dest);
        } catch (IOException e) {
            BedrockLog.warn("[BedrockConverter] Failed to copy flat icon " + source.getPath()
                    + " -> " + dest.getPath() + ": " + e.getMessage());
            return false;
        }
        iconTextureMap.put(safeId, iconRel);
        return true;
    }

    private static void generateItemTexture(Map<String, String> iconTextureMap, File bedrockDir) {
        JsonObject textureData = new JsonObject();
        for (Map.Entry<String, String> entry : iconTextureMap.entrySet()) {
            JsonObject texEntry = new JsonObject();
            texEntry.addProperty("textures", entry.getValue());
            textureData.add(entry.getKey(), texEntry);
        }

        JsonObject root = new JsonObject();
        root.addProperty("resource_pack_name", BEDROCK_PACK_NAME);
        root.addProperty("texture_name", "atlas.items");
        root.add("texture_data", textureData);

        File outputFile = new File(bedrockDir, "textures/item_texture.json");
        try {
            Files.createDirectories(outputFile.getParentFile().toPath());
            try (FileWriter writer = new FileWriter(outputFile, StandardCharsets.UTF_8)) {
                new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
                        .toJson(root, writer);
            }
        } catch (IOException e) {
            BedrockLog.warn("[BedrockConverter] Failed to write item_texture.json: " + e.getMessage());
        }
    }

    private static void copyPackIcon(File mergedPackRoot, File bedrockDir) {
        File packPng = new File(mergedPackRoot, "pack.png");
        if (!packPng.exists()) return;
        try {
            Files.copy(packPng.toPath(), new File(bedrockDir, "pack_icon.png").toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            BedrockLog.warn("Failed to copy pack icon: " + e.getMessage());
        }
    }

    private static void recursivelyDelete(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    recursivelyDelete(child);
                }
            }
        }
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException e) {
            BedrockLog.warn("Failed to delete " + file.getPath() + ": " + e.getMessage());
        }
    }
}
