package com.magmaguy.resourcepackmanager.bedrock;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
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
import com.magmaguy.resourcepackmanager.bedrock.model.FmmBoneModel;
import com.magmaguy.resourcepackmanager.bedrock.util.BedrockZip;
import com.magmaguy.resourcepackmanager.config.DefaultConfig;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Main orchestrator for converting FreeMinecraftModels bone models into a Bedrock
 * resource pack and Geyser custom mappings file.
 * <p>
 * Pipeline: scan FMM models → stitch textures → convert geometry → generate
 * attachables → generate Geyser v2 mappings → zip → deploy to Geyser.
 */
public class BedrockConversion {

    private static final String BEDROCK_PACK_NAME = "ResourcePackManager_Bedrock";
    private static final String GEYSER_MAPPINGS_NAME = "rspm_geyser_mappings.json";

    /**
     * Called early during RSPM startup (before Geyser scans its {@code custom_mappings/}
     * folder) to copy the previous run's Geyser mappings into place so that Geyser
     * registers our custom-item identifiers at its own boot. The Bedrock pack ZIP is
     * NOT pre-deployed here — {@link GeyserPackProvider} serves it live per-session
     * directly from {@code output/}, so a stale pre-deploy can't poison Geyser's
     * in-memory pack cache for the rest of the session anymore.
     * <p>
     * Mappings still need this pre-boot copy because Geyser's
     * {@code GeyserDefineCustomItemsEvent} is a lifecycle event that fires exactly
     * once at startup — there is no per-session mapping refresh, so changes to the
     * custom-item SET require a server restart to take effect. Texture/model tweaks
     * to existing items, in contrast, take effect for the next joining Bedrock
     * player without a restart (handled by {@link GeyserPackProvider}).
     */
    public static void deployPreviousMappingsIfNeeded() {
        if (!DefaultConfig.isBedrockConversionEnabled()) return;
        if (!DefaultConfig.isBedrockAutoDeployToGeyser()) return;

        File outputDir = new File(ResourcePackManager.plugin.getDataFolder(), "output");
        File previousMappings = new File(outputDir, GEYSER_MAPPINGS_NAME);

        if (!previousMappings.exists()) return;

        GeyserDeployer.deployMappings(previousMappings);
        Logger.info("Pre-deployed previous Geyser mappings for boot-time registration.");
    }

    /**
     * Main entry point. Called from Mix.java after the Java pack is zipped
     * but before the unzipped folder is cleaned up.
     *
     * @param mergedJavaPack the unzipped merged Java resource pack directory
     * @param outputDir      the output directory containing the merged pack
     */
    public static void generate(File mergedJavaPack, File outputDir) {
        try {
            Logger.info("Starting FMM Bedrock resource pack conversion for GeyserMC...");

            // 1. Scan for FMM bone models
            Map<String, List<FmmBoneModel>> modelGroups = FmmModelScanner.scan(mergedJavaPack);
            if (modelGroups.isEmpty()) {
                Logger.info("No FMM models found. Skipping Bedrock conversion.");
                return;
            }

            // 2. Create Bedrock pack directory
            File bedrockDir = new File(outputDir, BEDROCK_PACK_NAME);
            if (bedrockDir.exists()) recursivelyDelete(bedrockDir);
            bedrockDir.mkdirs();

            // Copy pack icon
            copyPackIcon(mergedJavaPack, bedrockDir);

            // 3. Process each model group
            List<FmmGeyserMappingBuilder.BoneMapping> allMappings = new ArrayList<>();
            Map<String, String> iconTextureMap = new LinkedHashMap<>(); // iconKey -> bedrockTexturePath
            int totalBones = 0;
            int convertedBones = 0;

            for (Map.Entry<String, List<FmmBoneModel>> entry : modelGroups.entrySet()) {
                String modelName = entry.getKey();
                List<FmmBoneModel> bones = entry.getValue();
                totalBones += bones.size();

                // 3a. Stitch textures for this model
                List<File> boneFiles = bones.stream()
                        .map(FmmBoneModel::getModelFile)
                        .collect(Collectors.toList());
                TextureStitcher.StitchResult stitch = TextureStitcher.stitch(
                        modelName, boneFiles, mergedJavaPack, bedrockDir);
                if (stitch == null) {
                    Logger.warn("Failed to stitch textures for model " + modelName + ", skipping.");
                    continue;
                }

                // 3b. Convert each bone
                for (FmmBoneModel bone : bones) {
                    JsonObject javaModel = parseJsonFile(bone.getModelFile());
                    if (javaModel == null) continue;

                    // Convert geometry
                    String geometryId = FmmGeometryConverter.convert(
                            modelName, bone.getBoneName(), javaModel,
                            stitch.spriteMap(), stitch.atlasWidth(), stitch.atlasHeight(),
                            bedrockDir);
                    if (geometryId == null) continue;

                    // Generate attachable + animation. Pass the parsed Java bone JSON so
                    // FmmAttachableGenerator can forward display.head into the animation
                    // (otherwise FMM's translation=(0,-6.4,0) + scale=4.0 are lost and the
                    // bone renders at the wrong position/scale on Bedrock).
                    String bedrockId = FmmAttachableGenerator.generate(
                            modelName, bone.getBoneName(),
                            geometryId, stitch.bedrockTexturePath(),
                            javaModel,
                            bedrockDir);
                    if (bedrockId == null) continue;

                    // Record mapping. Icon must be provided — Geyser docs
                    // (geysermc.org/wiki/geyser/custom-items) state that when
                    // bedrock_options.icon is absent, Geyser falls back to a
                    // bedrock-identifier-derived texture key (`:`→`.`, `/`→`_`); if no such
                    // entry exists in item_texture.json the item renders invisible because
                    // attachables don't provide an inventory thumbnail (they only render
                    // when equipped). The per-bone primary icon is the UV-unwrap atlas for
                    // 3D models without a flat-builtin parent — visually imperfect but at
                    // least the item is visible. Future improvement: render a 2D thumbnail
                    // from the 3D model via display.gui transform.
                    String iconKey = "fmm." + modelName + "." + bone.getBoneName();
                    String iconPath = stitch.bonePrimaryIconPath().get(bone.getBoneName());
                    if (iconPath == null) iconPath = stitch.bedrockTexturePath();
                    iconTextureMap.put(iconKey, iconPath);
                    // No display_name: the bone name carries no extra information
                    // over the bedrock_identifier, so passing null lets the mapping
                    // builder omit the field (Rainbow parity).
                    allMappings.add(new FmmGeyserMappingBuilder.BoneMapping(
                            bone.getItemModelKey(),
                            bedrockId,
                            null,
                            iconKey
                    ));
                    convertedBones++;
                }
            }

            if (allMappings.isEmpty()) {
                Logger.warn("No FMM bones could be converted. Skipping Bedrock pack generation.");
                recursivelyDelete(bedrockDir);
                return;
            }

            // === Generic Java->Bedrock pipeline (Phases 5+6: 2D icons + 3D models) ===
            // Runs BEFORE generateItemTexture so icons merge into iconTextureMap. The
            // populated registry is read back by the merged Geyser mapping emitter (Phase 7).
            MappedItemRegistry registry = new MappedItemRegistry();
            runGenericPipeline(mergedJavaPack, bedrockDir, iconTextureMap, registry);

            // (Equipment-as-worn-armor enrichment now lives inside the generic pipeline.
            //  Geyser's `model` field matches Java item_model, NOT equippable.asset_id —
            //  so worn-armor attachables MUST be keyed on the items-pipeline bedrock
            //  identifier, not on the equipment asset_id. See
            //  EquipmentAttachableGenerator.tryEnrichWithArmorAttachable for the per-base
            //  enrichment hook fired from the flat-icon emission loop.)

            // 4. Generate item_texture.json (required by Geyser for icon resolution)
            generateItemTexture(iconTextureMap, bedrockDir);

            // 5. Generate manifest. The cache-bust token is just the build timestamp —
            // enough to bump the manifest version triplet per build so Bedrock invalidates
            // its (uuid, version)-keyed pack cache. Not a hash of pack contents, so
            // rebuilding identical contents still produces a new version (i.e. no
            // reproducible-build property).
            String cacheBustToken = String.valueOf(System.currentTimeMillis());
            String pluginVersion = ResourcePackManager.plugin.getDescription().getVersion();
            BedrockManifest.write(bedrockDir, pluginVersion, cacheBustToken);

            // 6. Generate merged Geyser mappings (FMM entries under leather_horse_armor +
            //    generic entries grouped by their candidate base item).
            File mappingsFile = new File(outputDir, GEYSER_MAPPINGS_NAME);
            GenericGeyserMappingBuilder.merge(allMappings, registry, mappingsFile);

            // 7. Zip
            File bedrockZip = BedrockZip.zip(bedrockDir, outputDir, BEDROCK_PACK_NAME);
            if (bedrockZip == null) {
                Logger.warn("Failed to zip Bedrock resource pack!");
                return;
            }
            recursivelyDelete(bedrockDir);

            // 8. Deploy mappings to Geyser (mappings only — the pack zip is served live
            //    per Bedrock session by GeyserPackProvider, no copy needed). Mappings
            //    landed here apply on NEXT server boot, since Geyser's custom-item
            //    registry is boot-frozen.
            if (DefaultConfig.isBedrockAutoDeployToGeyser()) {
                GeyserDeployer.deployMappings(mappingsFile);
            }

            Logger.info("FMM Bedrock conversion complete: " + convertedBones + "/" + totalBones
                    + " bones from " + modelGroups.size() + " models.");
            Logger.info("Bedrock pack: " + bedrockZip.getAbsolutePath());
            Logger.info("Geyser mappings: " + mappingsFile.getAbsolutePath());
            Logger.info("Bedrock pack is served live per-session; mapping changes apply on next server restart.");

        } catch (Exception e) {
            Logger.warn("FMM Bedrock conversion failed: " + e.getMessage());
            e.printStackTrace();
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
     * generic icons.
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
                    if (modelOpt.isEmpty()) {
                        Logger.warn("[BedrockConverter] Could not resolve model " + leaf.modelRef()
                                + " (referenced by " + def.itemIdentifier() + "); skipping");
                        continue;
                    }
                    ResolvedModel resolved = modelOpt.get();

                    // Split namespace and path from the model reference (e.g.
                    // "elitemobs:gear/bronze_sword" → ns="elitemobs", path="gear/bronze_sword").
                    int colon = leaf.modelRef().indexOf(':');
                    String namespace = colon > 0 ? leaf.modelRef().substring(0, colon) : "minecraft";
                    String rawPath = colon > 0 ? leaf.modelRef().substring(colon + 1) : leaf.modelRef();
                    // Path with slashes turned into underscores. Rainbow-style identifier:
                    // namespace stays, but the path's '/' becomes '_' (matches
                    // Rainbow.bedrockSafeIdentifier *only on the path component*, leaving
                    // the colon intact). See BedrockItemMapper.java:256-261.
                    String pathPart = rawPath.replace('/', '_');
                    // iconKey is shared across every per-base tier of this model. It mirrors
                    // Rainbow's GeyserBaseDefinition.textureName default, computed from the
                    // model identifier (NOT the per-tier bedrock identifier): '<ns>.<path>'.
                    String iconKey = namespace + "." + pathPart;

                    if (resolved.isFlatBuiltin()) {
                        // 2D path: bedrock_identifier must be UNIQUE per (model × base item).
                        // Geyser rejects duplicate bedrock_identifiers across base items even
                        // when no attachable file is emitted — evidence: Geyser server log
                        // emits `Not registering custom item definition (bedrock identifier=X):
                        // conflicts with another custom item definition` for every duplicate.
                        // The icon key stays SHARED across tiers (one item_texture.json entry
                        // per model — same inventory icon regardless of base item).
                        boolean firstTime = registry.registerModelOnce(leaf.modelRef());
                        if (firstTime) {
                            if (!emitFlatIcon(resolved, iconKey, mergedJavaPack, bedrockDir, iconTextureMap)) {
                                // Failed to copy texture; skip mappings for this model so the
                                // client doesn't get a dangling icon reference.
                                continue;
                            }
                        }
                        // Items filename stem used by armor-attachable enrichment (below).
                        // Derived from the OUTER ItemsDefinition path, NOT the leaf modelRef:
                        // e.g. items/gear/bronze_chestplate.json -> "bronze_chestplate". The
                        // model ref points at a sibling icon model (`..._icon`) and would
                        // strip wrong with `_icon` left over.
                        String itemsStem = def.itemsRelPath();
                        // The Geyser `model` field must equal the Java ItemStack's
                        // `minecraft:item_model` component value, which plugins set via
                        // ItemMeta#setItemModel(NamespacedKey) using the ITEMS-DEFINITION
                        // identifier (e.g. "elitemobs:gear/bronze_chestplate"). For items
                        // using `display_context` select, the resolved leaf modelRef
                        // (e.g. "elitemobs:gear/bronze_chestplate_icon") DIFFERS from the
                        // items-def identifier — using the leaf would cause Geyser to never
                        // match the runtime item. Use def.itemIdentifier().
                        String javaItemModel = def.itemIdentifier();
                        for (String base : baseItems) {
                            String baseSafe = base.replace("minecraft:", "").replace(':', '_').replace('/', '_');
                            String tierBedrockId = namespace + ":" + pathPart + "__" + baseSafe;
                            registry.addMapping(base, new GeyserDefinitionEntry(
                                    tierBedrockId,
                                    javaItemModel,
                                    leaf.predicates(),
                                    iconKey,
                                    resolved.isHandheldVariant()
                            ));
                            // Worn-armor attachable enrichment. Fires only when:
                            //   - the base item IS an armor item (helmet/chestplate/leggings/boots/elytra), AND
                            //   - the items namespace has a sibling equipment/<materialStem>.json file.
                            // The attachable is keyed on tierBedrockId so Geyser's item_model->mapping->
                            // bedrock_identifier->attachable chain finds it when the player wears the item.
                            // Quietly returns false for non-armor bases (e.g. swords vs leather_helmet —
                            // shouldn't happen since baseItems comes from BaseItemResolver, but defensive).
                            String attachableOut = namespace + "/" + pathPart + "__" + baseSafe;
                            EquipmentAttachableGenerator.tryEnrichWithArmorAttachable(
                                    tierBedrockId, attachableOut, namespace, itemsStem,
                                    base, assetResolver, mergedJavaPack, bedrockDir);
                        }
                        flatEmitted++;
                    } else {
                        // 3D path (Phase G): one geometry/anim/atlas per model, but ONE attachable
                        // per (model × base item) with a unique bedrock_identifier per tier.
                        // Pass def.itemIdentifier() so the Geyser `model` field matches the
                        // Java ItemStack's item_model component (see comment above).
                        if (!emitGenericThreeD(leaf, resolved, namespace, pathPart, iconKey,
                                def.itemIdentifier(),
                                baseItems, registry, modelAssetsCache,
                                mergedJavaPack, bedrockDir, iconTextureMap)) {
                            continue;
                        }
                        threeEmitted++;
                    }
                }
            }

            Logger.info("[BedrockConverter] Generic pipeline: emitted "
                    + flatEmitted + " flat-icon leaves and " + threeEmitted + " 3D leaves; "
                    + registry.uniqueModelsWritten() + " unique models written; "
                    + registry.totalMappings() + " total mappings accumulated (to be merged with FMM mappings).");
        } catch (Exception e) {
            Logger.warn("[BedrockConverter] Generic pipeline failed: " + e.getMessage());
        }
    }

    /**
     * Phase G 3D leaf emission. Splits work into:
     * <ul>
     *   <li><b>Per-model (once)</b>: stitch atlas, convert geometry, prepare animation
     *       file, register icon. Cached in {@code modelAssetsCache} keyed by Java model ref.</li>
     *   <li><b>Per-(model × base item)</b>: emit one attachable JSON with a UNIQUE
     *       {@code bedrock_identifier} of form {@code <ns>:<path>__<baseSafe>} and
     *       register a Geyser mapping entry pointing the base item at that identifier.</li>
     * </ul>
     * The unique-per-tier bedrock identifier is what unblocks Geyser's globally-unique
     * identifier requirement — previously every sword tier shared the same identifier
     * and only the first alphabetical entry survived registration.
     *
     * @return {@code true} if at least one base-item attachable was successfully emitted,
     *         {@code false} if the per-model emission failed (no mappings registered).
     */
    private static boolean emitGenericThreeD(ResolvedLeaf leaf,
                                             ResolvedModel resolved,
                                             String namespace,
                                             String pathPart,
                                             String iconKey,
                                             String javaItemModel,
                                             List<String> baseItems,
                                             MappedItemRegistry registry,
                                             Map<String, SharedModelAssets> modelAssetsCache,
                                             File mergedJavaPack,
                                             File bedrockDir,
                                             Map<String, String> iconTextureMap) {
        // Top-level "modelName" groups by plugin namespace; each item is one "bone".
        String genericModelName = namespace;
        String modelSafeBoneName = pathPart;

        SharedModelAssets shared;
        if (registry.registerModelOnce(leaf.modelRef())) {
            // ---- Per-model one-time emission (geometry, atlas, animation, icon) ----
            TextureStitcher.StitchResult stitch = TextureStitcher.stitchSingleModel(
                    genericModelName, modelSafeBoneName, resolved.mergedJson(),
                    mergedJavaPack, bedrockDir);
            if (stitch == null) {
                Logger.warn("[BedrockConverter] Failed to stitch textures for generic 3D model "
                        + leaf.modelRef() + "; skipping");
                return false;
            }

            String geometryIdentifier = "geometry." + namespace + "." + pathPart;
            String geometryOutputPath = genericModelName + "/" + modelSafeBoneName;
            String resultGeoId = FmmGeometryConverter.convertWithIdentifier(
                    geometryIdentifier, geometryOutputPath, resolved.mergedJson(),
                    stitch.spriteMap(), stitch.atlasWidth(), stitch.atlasHeight(),
                    bedrockDir);
            if (resultGeoId == null) {
                Logger.warn("[BedrockConverter] Failed to convert geometry for generic 3D model "
                        + leaf.modelRef() + "; skipping");
                return false;
            }

            String animBaseId = namespace + "." + pathPart;
            String animFileBase = genericModelName + "__" + modelSafeBoneName;
            FmmAnimationGenerator.AnimationIds animIds = FmmAttachableGenerator.prepareAnimations(
                    animBaseId, animFileBase, resolved.mergedJson(), bedrockDir);
            if (animIds == null) {
                Logger.warn("[BedrockConverter] Failed to prepare animations for generic 3D model "
                        + leaf.modelRef() + "; skipping");
                return false;
            }

            // Icon: render a proper 2D inventory thumbnail by software-rasterizing the
            // Java 3D model through its `display.gui` transform (or the Mojang default
            // `rotation: [30, 225, 0]` when none is supplied). Falls back to a Mojang-
            // style magenta/black checkerboard if rendering fails (e.g. model is
            // textureless or references a missing PNG).
            //
            // Geyser docs (geysermc.org/wiki/geyser/custom-items): if
            // bedrock_options.icon is absent, Geyser uses the bedrock_identifier as the
            // texture key (`:`→`.`, `/`→`_`) — that synthetic key has no matching entry
            // in item_texture.json and Bedrock attachables don't render in inventory
            // slots (only when equipped), so omitting the icon renders the item
            // INVISIBLE in hotbar/inventory. We therefore always emit an icon file and
            // wire it into item_texture.json via {@code iconTextureMap}.
            String safeIconStem = iconKey; // already shape "<ns>.<pathPart>" -> safe filename
            String iconRel = "textures/items/" + safeIconStem;
            File iconFile = new File(bedrockDir, iconRel + ".png");
            boolean rendered = IconRenderer.renderIcon(resolved.mergedJson(), mergedJavaPack, iconFile);
            if (!rendered) {
                Logger.warn("[BedrockConverter] Icon rendering failed for " + leaf.modelRef()
                        + "; using missing-texture placeholder");
                IconRenderer.writeMissingPlaceholder(iconFile);
            }
            iconTextureMap.put(iconKey, iconRel);

            shared = new SharedModelAssets(stitch, resultGeoId, animIds);
            modelAssetsCache.put(leaf.modelRef(), shared);
        } else {
            shared = modelAssetsCache.get(leaf.modelRef());
            if (shared == null) {
                // First-pass emission failed (registry recorded the model, then the
                // per-model build aborted). Drop subsequent leaves silently.
                return false;
            }
        }

        // ---- Per-(model × base item) emission: one unique-id attachable each ----
        boolean anyEmitted = false;
        for (String base : baseItems) {
            String baseSafe = base.replace("minecraft:", "").replace(':', '_').replace('/', '_');
            String tierBedrockId = namespace + ":" + pathPart + "__" + baseSafe;
            String attachableOutPath = genericModelName + "/" + modelSafeBoneName + "__" + baseSafe;

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

    /**
     * Per-model shared bedrock assets cached during a single generic-pipeline run.
     * Emitted once per Java model ref; referenced by every per-(model × base item)
     * attachable so geometry, animation, and atlas files exist exactly once on disk.
     */
    private record SharedModelAssets(
            TextureStitcher.StitchResult stitch,
            String geometryId,
            FmmAnimationGenerator.AnimationIds animIds) {
    }

    /**
     * Emits a 2D icon for a flat-builtin model: copies the layer0 source PNG to
     * {@code textures/items/<safeId>.png} and registers the icon key in {@code iconTextureMap}.
     * Returns true on success, false if the layer0 reference can't be resolved or the file
     * copy fails.
     */
    private static boolean emitFlatIcon(ResolvedModel resolved, String safeId,
                                        File mergedJavaPack, File bedrockDir,
                                        Map<String, String> iconTextureMap) {
        // The merged JSON has textures.layer0 (or textures.0 in old custom_model_data packs).
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
            Logger.warn("[BedrockConverter] Flat-icon model " + resolved.identifier()
                    + " has no layer0/0 texture reference; skipping");
            return false;
        }
        // Resolve "ns:path" -> assets/ns/textures/path.png
        int colon = layer0.indexOf(':');
        String ns = colon >= 0 ? layer0.substring(0, colon) : "minecraft";
        String texPath = colon >= 0 ? layer0.substring(colon + 1) : layer0;
        File source = new File(mergedJavaPack, "assets/" + ns + "/textures/" + texPath + ".png");
        if (!source.isFile()) {
            Logger.warn("[BedrockConverter] Flat-icon texture not found: " + source.getPath());
            return false;
        }
        String iconRel = "textures/items/" + safeId;
        File dest = new File(bedrockDir, iconRel + ".png");
        try {
            Files.createDirectories(dest.getParentFile().toPath());
            // Crop vertical flipbook icons (e.g. EliteMobs `bronzesword.png` is 64x768
            // with a sibling `.png.mcmeta`) to the top frame before writing. Bedrock
            // does NOT support flipbook animation for item icons (item_texture.json
            // entries), only blocks/terrain via flipbook_textures.json. A verbatim
            // 64x768 copy renders as a tall squished icon, so we emit frame 0 as a
            // square static icon instead.
            TextureStitcher.writeIconCroppedIfFlipbook(source, dest);
        } catch (IOException e) {
            Logger.warn("[BedrockConverter] Failed to copy flat icon " + source.getPath()
                    + " -> " + dest.getPath() + ": " + e.getMessage());
            return false;
        }
        iconTextureMap.put(safeId, iconRel);
        return true;
    }

    /**
     * Generates item_texture.json in the Bedrock pack.
     * Maps each bone's icon key to its model's atlas texture path.
     * Required by Geyser to resolve item icons for custom items.
     */
    private static void generateItemTexture(Map<String, String> iconTextureMap, File bedrockDir) {
        JsonObject textureData = new JsonObject();
        for (Map.Entry<String, String> entry : iconTextureMap.entrySet()) {
            JsonObject texEntry = new JsonObject();
            texEntry.addProperty("textures", entry.getValue());
            textureData.add(entry.getKey(), texEntry);
        }

        JsonObject root = new JsonObject();
        root.addProperty("resource_pack_name", "ResourcePackManager_Bedrock");
        root.addProperty("texture_name", "atlas.items");
        root.add("texture_data", textureData);

        File outputFile = new File(bedrockDir, "textures/item_texture.json");
        try {
            Files.createDirectories(outputFile.getParentFile().toPath());
            try (FileWriter writer = new FileWriter(outputFile, StandardCharsets.UTF_8)) {
                new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
                        .toJson(root, writer);
            }
            Logger.info("[BedrockConverter] Generated item_texture.json with " + iconTextureMap.size() + " entries.");
        } catch (IOException e) {
            Logger.warn("[BedrockConverter] Failed to write item_texture.json: " + e.getMessage());
        }
    }

    /**
     * Parses a JSON file into a JsonObject.
     */
    private static JsonObject parseJsonFile(File file) {
        try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            Logger.warn("[BedrockConverter] Failed to parse " + file.getPath() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Copies pack.png from the Java pack root to pack_icon.png in the Bedrock pack root.
     */
    private static void copyPackIcon(File mergedPackRoot, File bedrockDir) {
        File packPng = new File(mergedPackRoot, "pack.png");
        if (!packPng.exists()) return;
        try {
            Files.copy(packPng.toPath(), new File(bedrockDir, "pack_icon.png").toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Logger.warn("Failed to copy pack icon: " + e.getMessage());
        }
    }

    /**
     * Recursively deletes a directory and all of its contents.
     */
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
            Logger.warn("Failed to delete " + file.getPath() + ": " + e.getMessage());
        }
    }
}
