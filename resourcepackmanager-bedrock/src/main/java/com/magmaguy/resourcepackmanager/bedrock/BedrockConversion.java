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
import com.magmaguy.resourcepackmanager.bedrock.util.ExactTextureFileCache;
import com.magmaguy.resourcepackmanager.mixer.bedrock.BedrockPackOptimizer;
import com.magmaguy.resourcepackmanager.mixer.engine.internal.AsyncDirectoryCleaner;
import com.magmaguy.resourcepackmanager.mixer.engine.internal.Cancellation;
import com.magmaguy.resourcepackmanager.mixer.engine.internal.PackFileIndex;
import com.magmaguy.resourcepackmanager.mixer.engine.internal.Sha1;
import com.magmaguy.resourcepackmanager.mixer.engine.internal.ZipUtil;
import com.magmaguy.resourcepackmanager.mixer.engine.MixerLogger;

import java.io.File;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipFile;

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
    public static final String ARTIFACT_SET_MANIFEST_NAME = "rspm_artifact_set.json";
    public static final int ARTIFACT_SET_MANIFEST_VERSION = 1;

    private static final MixerLogger PACK_OPTIMIZER_LOGGER = new MixerLogger() {
        @Override public void info(String message) { BedrockLog.debug(message); }
        @Override public void warn(String message) { BedrockLog.warn(message); }
        @Override public void collision(String message) { }
    };

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
        File previousMappings = ctx.isBedrockConversionEnabled()
                ? ctx.previousMappingsFile()
                : null;

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
     * @return {@code true} when conversion completed or no Bedrock output is
     *         required; {@code false} when it was cancelled or failed
     */
    public static boolean generate(File mergedJavaPack, File outputDir, BedrockConverterContext ctx) {
        BedrockLog.set(ctx.logger());
        BedrockLog.setDebug(ctx.isBedrockConverterDebug());
        BedrockDisplayOffsets.set(ctx.displayOffsets());
        File publicationStaging = new File(outputDir,
                ".rspm-bedrock-publication-" + UUID.randomUUID());
        File pendingMappings = new File(publicationStaging, GEYSER_MAPPINGS_NAME);
        boolean publicationReplacementAttempted = false;
        try {
            Files.createDirectories(publicationStaging.toPath());
            if (ctx.isCancellationRequested()) return false;
            if (!ctx.isBedrockConversionEnabled()) {
                return removePublishedOutputs(outputDir, ctx);
            }
            if (!ctx.isBedrockTargetPresent()) {
                return removePublishedOutputs(outputDir, ctx);
            }

            // Per-mix "Starting Bedrock resource pack conversion..." fires every
            // /reload and every mix cycle; demoted to debug so a clean run only
            // emits the single "Bedrock conversion complete: N mappings" summary.
            BedrockLog.debug("Starting Bedrock resource pack conversion for GeyserMC...");

            // Create the Bedrock pack staging directory and copy the pack icon.
            File bedrockDir = new File(outputDir, BEDROCK_PACK_NAME);
            // Rename-then-delete: frees the path immediately so conversion starts now rather than
            // after the previous cycle's ~10k files have been unlinked.
            if (bedrockDir.exists()) deleteOffCriticalPath(bedrockDir);
            bedrockDir.mkdirs();
            copyPackIcon(mergedJavaPack, bedrockDir);

            // Single, namespace-agnostic conversion pipeline. The generic scanner walks
            // every items definition under assets/<ns>/items/** (including the
            // freeminecraftmodels namespace — there is no longer a FMM-specific path).
            // Icons are accumulated into iconTextureMap as a side-effect.
            Map<String, String> iconTextureMap = new LinkedHashMap<>();
            MappedItemRegistry registry = new MappedItemRegistry();
            runGenericPipeline(mergedJavaPack, bedrockDir, iconTextureMap, registry, ctx);
            if (ctx.isCancellationRequested()) return false;
            int entityBundleFiles = BedrockEntityBundleImporter.importBundles(
                    mergedJavaPack,
                    bedrockDir,
                    ctx::isCancellationRequested);
            if (ctx.isCancellationRequested()) return false;

            // No convertible content (no custom items / models in the merged Java pack).
            // Per user policy: do NOT fall back to a manifest-only minimal pack. Either
            // we have real content or we emit nothing. This avoids a useless 22KB pack
            // with a prompt when the backend has nothing to ship. Also nuke any stale
            // output from the previous run so the backend's /bedrock.zip route 404s
            // cleanly instead of serving last cycle's content forever.
            if (registry.totalMappings() == 0 && entityBundleFiles == 0) {
                // No convertible content this cycle. Clear out previous-run output
                // so the backend's /bedrock.zip route 404s cleanly instead of
                // serving last cycle's content forever. Silent — operator doesn't
                // need to know about routine cleanup.
                deleteOffCriticalPath(bedrockDir);
                return removePublishedOutputs(outputDir, ctx);
            }

            // 4. Generate item_texture.json (required by Geyser for icon resolution).
            if (!iconTextureMap.isEmpty()) {
                generateItemTexture(iconTextureMap, bedrockDir);
            }
            if (ctx.isCancellationRequested()) return false;

            // Share byte-identical generated/imported textures rather than shipping a
            // separate copy for every model. The optimizer rewrites exact JSON paths
            // first and retains aliases whenever a reference is structurally ambiguous.
            BedrockPackOptimizer.deduplicateExactTextures(
                    bedrockDir.toPath(),
                    PACK_OPTIMIZER_LOGGER,
                    ctx::isCancellationRequested);
            if (ctx.isCancellationRequested()) return false;

            // 5. Generate manifest. Use a digest of the staged pack content before
            // manifest.json is written, so identical resource-pack contents produce the
            // same Bedrock pack version and startup remixes do not churn Geyser's live
            // pack file. Real content changes still bump Bedrock's (uuid, version)
            // cache key.
            String cacheBustToken = contentDigest(bedrockDir, ctx::isCancellationRequested);
            if (ctx.isCancellationRequested()) return false;
            String pluginVersion = ctx.pluginVersion();
            BedrockManifest.write(bedrockDir, pluginVersion, cacheBustToken);

            // 6. Generate merged Geyser mappings.
            File mappingsFile = new File(outputDir, GEYSER_MAPPINGS_NAME);
            if (registry.totalMappings() > 0) {
                GenericGeyserMappingBuilder.merge(registry, pendingMappings);
            } else {
                Files.deleteIfExists(pendingMappings.toPath());
            }
            if (ctx.isCancellationRequested()) return false;

            // The ZIP is the commit artifact for both direct HTTP and relay
            // delivery.  Its embedded manifest binds the optional mappings
            // sidecar to this exact conversion generation, allowing consumers
            // to reject a ZIP/mappings pair observed across two publication
            // commits without requiring a new relay-server API.
            writeArtifactSetManifest(
                    bedrockDir,
                    cacheBustToken,
                    pluginVersion,
                    registry.totalMappings() > 0 ? pendingMappings : null);
            if (ctx.isCancellationRequested()) return false;

            // 7. Zip
            File bedrockZip = BedrockZip.zip(
                    bedrockDir,
                    publicationStaging,
                    BEDROCK_PACK_NAME,
                    ctx::isCancellationRequested);
            if (bedrockZip == null) {
                if (ctx.isCancellationRequested()) return false;
                BedrockLog.warn("Failed to zip Bedrock resource pack!");
                removePublishedOutputs(outputDir, ctx);
                return false;
            }

            if (ctx.isCancellationRequested()) return false;

            // Commit the ZIP, local sidecar and next-boot Geyser mapping as one
            // rollback-capable artifact set. The route/provider authority marker
            // is committed while rollback snapshots are still retained, so a
            // cancelled or failed pre-commit replacement restores last-good bytes.
            publicationReplacementAttempted = true;
            publishArtifactSet(
                    bedrockZip.toPath(),
                    registry.totalMappings() > 0 ? pendingMappings.toPath() : null,
                    new File(outputDir, BEDROCK_PACK_NAME + ".zip").toPath(),
                    mappingsFile.toPath(),
                    outputDir,
                    ctx);

            // The pack and its mappings are published; the staging tree is now only disk to
            // reclaim, so it must not delay the caller.
            deleteOffCriticalPath(bedrockDir);

            BedrockLog.info("Bedrock conversion complete: " + registry.totalMappings() + " mappings ("
                    + registry.uniqueModelsWritten() + " unique models, "
                    + entityBundleFiles + " custom entity bundle files).");
            return true;

        } catch (Exception e) {
            if (ctx.isCancellationRequested() || e instanceof CancellationException) {
                return false;
            }
            BedrockLog.warn("Bedrock conversion failed: " + e.getMessage());
            e.printStackTrace();
            // Once the publication transaction begins it either commits the
            // new set or restores the old one. Do not authoritatively delete a
            // successfully restored last-good set merely because this cycle
            // failed; the platform caller decides whether valid authority remains.
            if (!publicationReplacementAttempted) removePublishedOutputs(outputDir, ctx);
            return false;
        } finally {
            recursivelyDelete(publicationStaging);
            BedrockLog.set(null);
            BedrockDisplayOffsets.set(null);
        }
    }

    private static boolean removePublishedOutputs(File outputDir, BedrockConverterContext ctx) {
        boolean removed = true;
        File bedrockZip = new File(outputDir, BEDROCK_PACK_NAME + ".zip");
        File mappings = new File(outputDir, GEYSER_MAPPINGS_NAME);
        // Authority withdrawal is deliberately first. Locked leftovers are
        // inert once the platform tombstone/revocation is installed and cannot
        // make the caller's current() check re-authorize disabled output.
        try {
            if (!ctx.withdrawPublishedArtifactSet(outputDir)) removed = false;
        } catch (RuntimeException e) {
            removed = false;
            BedrockLog.warn("Failed to withdraw Bedrock publication authority: "
                    + e.getMessage());
        }
        try {
            Files.deleteIfExists(bedrockZip.toPath());
        } catch (IOException e) {
            removed = false;
            BedrockLog.warn("Failed to delete stale Bedrock pack zip: " + e.getMessage());
        }
        try {
            Files.deleteIfExists(mappings.toPath());
        } catch (IOException e) {
            removed = false;
            BedrockLog.warn("Failed to delete stale Geyser mappings: " + e.getMessage());
        }
        try {
            ctx.removeDeployedMappingsIfNeeded();
        } catch (RuntimeException e) {
            removed = false;
            BedrockLog.warn("Failed to remove deployed Geyser mappings: " + e.getMessage());
        }
        return removed;
    }

    /**
     * Publishes the complete Bedrock artifact set with byte-for-byte rollback.
     * All old destinations are snapshotted before the first mutation; a failure
     * at any later step restores both prior files and prior absences.
     */
    private static void publishArtifactSet(Path stagedZip,
                                           Path stagedMappings,
                                           Path publishedZip,
                                           Path publishedMappings,
                                           File outputDir,
                                           BedrockConverterContext ctx) throws IOException {
        Path backupDir = Files.createTempDirectory(
                publishedZip.toAbsolutePath().getParent(), ".rspm-bedrock-rollback-");
        File deployedMappingsFile = ctx.deployedMappingsFile();
        Path deployedMappings = deployedMappingsFile == null
                ? null
                : deployedMappingsFile.toPath().toAbsolutePath();
        File previousDeployedMappingsFile = ctx.previousDeployedMappingsFile();
        Path previousDeployedMappings = previousDeployedMappingsFile == null
                ? null
                : previousDeployedMappingsFile.toPath().toAbsolutePath();
        if (previousDeployedMappings != null && previousDeployedMappings.equals(deployedMappings)) {
            previousDeployedMappings = null;
        }
        PublicationBackup zipBackup = PublicationBackup.capture(publishedZip, backupDir, "pack.zip");
        PublicationBackup mappingsBackup = PublicationBackup.capture(
                publishedMappings, backupDir, "mappings.json");
        PublicationBackup deployedBackup = deployedMappings == null
                ? null
                : PublicationBackup.capture(deployedMappings, backupDir, "deployed-mappings.json");
        PublicationBackup previousDeployedBackup = previousDeployedMappings == null
                ? null
                : PublicationBackup.capture(
                previousDeployedMappings, backupDir, "previous-deployed-mappings.json");
        File provenanceFile = ctx.deployedMappingsProvenanceFile();
        Path provenance = provenanceFile == null ? null : provenanceFile.toPath().toAbsolutePath();
        PublicationBackup provenanceBackup = provenance == null
                ? null
                : PublicationBackup.capture(provenance, backupDir, "deployment-provenance.txt");
        try {
            ctx.beginPublishedArtifactSetMutation(outputDir);
            publishFrom(stagedMappings, publishedMappings);
            publishFrom(stagedZip, publishedZip);
            if (deployedMappings != null) {
                publishFrom(stagedMappings, deployedMappings);
            }
            if (previousDeployedMappings != null) {
                publishFrom(null, previousDeployedMappings);
            }
            if (provenance != null) {
                if (stagedMappings == null || deployedMappings == null) {
                    publishFrom(null, provenance);
                } else {
                    Path pendingProvenance = backupDir.resolve("next-deployment-provenance.txt");
                    Files.writeString(
                            pendingProvenance,
                            deployedMappings.toAbsolutePath().normalize() + System.lineSeparator(),
                            StandardCharsets.UTF_8);
                    publishFrom(pendingProvenance, provenance);
                }
            }
            if (ctx.isCancellationRequested()) throw new CancellationException();
            if (!ctx.commitPublishedArtifactSet(outputDir)) {
                if (ctx.isCancellationRequested()) throw new CancellationException();
                throw new IOException("Platform rejected the Bedrock artifact-set authority commit");
            }
        } catch (IOException | RuntimeException publicationFailure) {
            IOException rollbackFailure = null;
            rollbackFailure = restore(zipBackup, rollbackFailure);
            rollbackFailure = restore(mappingsBackup, rollbackFailure);
            rollbackFailure = restore(deployedBackup, rollbackFailure);
            rollbackFailure = restore(previousDeployedBackup, rollbackFailure);
            rollbackFailure = restore(provenanceBackup, rollbackFailure);
            if (rollbackFailure != null) {
                publicationFailure.addSuppressed(rollbackFailure);
                ctx.invalidatePublishedArtifactSet(outputDir);
            }
            throw publicationFailure;
        } finally {
            recursivelyDelete(backupDir.toFile());
        }
    }

    private static void publishFrom(Path sourceOrNull, Path target) throws IOException {
        if (sourceOrNull == null) {
            Files.deleteIfExists(target);
            return;
        }
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path pending = target.resolveSibling(
                "." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.copy(sourceOrNull, pending, StandardCopyOption.REPLACE_EXISTING);
            publishAtomically(pending, target);
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    private static IOException restore(PublicationBackup backup, IOException previous) {
        if (backup == null) return previous;
        try {
            if (backup.existed()) {
                publishFrom(backup.copy(), backup.target());
            } else {
                Files.deleteIfExists(backup.target());
            }
        } catch (IOException restoreFailure) {
            if (previous == null) return restoreFailure;
            previous.addSuppressed(restoreFailure);
        }
        return previous;
    }

    private record PublicationBackup(Path target, boolean existed, Path copy) {
        private static PublicationBackup capture(Path target, Path backupDir, String name)
                throws IOException {
            boolean existed = Files.exists(target);
            Path copy = backupDir.resolve(name);
            if (existed) Files.copy(target, copy, StandardCopyOption.REPLACE_EXISTING);
            return new PublicationBackup(target, existed, copy);
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
                                           MappedItemRegistry registry,
                                           BedrockConverterContext ctx) {
        try {
            List<ItemsDefinition> generic = GenericJavaScanner.scan(
                    mergedJavaPack,
                    ctx::isCancellationRequested);
            if (ctx.isCancellationRequested()) return;
            if (generic.isEmpty()) return;

            AssetResolver assetResolver = new AssetResolver(mergedJavaPack);
            int flatEmitted = 0;
            int threeEmitted = 0;
            // Phase G: cache of per-model assets (stitch + geometry id + animation triple)
            // keyed by Java model ref. Populated on first encounter of a model, reused for
            // every (model × base item) attachable emission.
            Map<String, SharedModelAssets> modelAssetsCache = new LinkedHashMap<>();
            ExactTextureFileCache exactAtlasCache = new ExactTextureFileCache();

            for (ItemsDefinition def : generic) {
                if (ctx.isCancellationRequested()) return;
                List<ResolvedLeaf> leaves = ItemModelTreeWalker.walk(def, assetResolver);
                List<String> baseItems = BaseItemResolver.resolve(def, assetResolver);
                if (baseItems.isEmpty()) continue;

                for (ResolvedLeaf leaf : leaves) {
                    if (ctx.isCancellationRequested()) return;
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

                    // Route on actual geometry, not parent identity. A model needs the
                    // 3D pipeline (stitch + geometry + attachable) ONLY if it carries an
                    // `elements` array. Flat icons go through the flat-icon path — and
                    // that includes more than vanilla item/generated: a flat handheld
                    // tool (parent minecraft:item/handheld with just a layer0 texture and
                    // no elements) is a 2D sprite too. Keying solely on isFlatBuiltin()
                    // (== item/generated|builtin/generated) misrouted every flat handheld
                    // into the 3D pipeline, where it died at the stitch/geometry step
                    // (FmmGeometryConverter requires elements). isFlatBuiltin() stays as a
                    // fast-path so a generated model with a stray elements block — which
                    // would never convert as 3D anyway — is still treated as flat.
                    boolean hasGeometry = resolved.mergedJson().has("elements")
                            && resolved.mergedJson().get("elements").isJsonArray()
                            && !resolved.mergedJson().getAsJsonArray("elements").isEmpty();
                    if (resolved.isFlatBuiltin() || !hasGeometry) {
                        boolean firstTime = registry.registerModelOnce(leaf.modelRef());
                        if (firstTime) {
                            if (!emitFlatIcon(resolved, iconKey, mergedJavaPack, bedrockDir, iconTextureMap)) {
                                continue;
                            }
                        }
                        String itemsStem = def.itemsRelPath();
                        String javaItemModel = def.itemIdentifier();
                        for (String base : baseItems) {
                            if (ctx.isCancellationRequested()) return;
                            String mappingHash = BedrockShortName.forBaseMapping(
                                    leaf.modelRef(), base, MappedItemRegistry.predicateShape(leaf.predicates()));
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
                            if (ctx.isCancellationRequested()) return;
                        }
                        flatEmitted++;
                    } else {
                        if (!emitGenericThreeD(leaf, resolved, modelHash, iconKey,
                                def.itemIdentifier(),
                                baseItems, registry, modelAssetsCache,
                                mergedJavaPack, bedrockDir, iconTextureMap, exactAtlasCache,
                                ctx::isCancellationRequested)) {
                            if (ctx.isCancellationRequested()) return;
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
            throw new IllegalStateException(
                    "[BedrockConverter] Generic pipeline failed: " + e.getMessage(), e);
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
                                             Map<String, String> iconTextureMap,
                                             ExactTextureFileCache exactAtlasCache,
                                             BooleanSupplier cancellationRequested) {
        if (isCancelled(cancellationRequested)) return false;
        // modelHash is the short, opaque, deterministic stem (see BedrockShortName)
        // used for every per-model file path in the Bedrock pack — texture atlas,
        // geometry, animation, and the iconKey under which the per-model rendered
        // inventory icon is registered with Geyser. Keeping a single stem across
        // all four file types means a future diff against the pack ZIP is trivial:
        // grep <modelHash> finds every file produced for that source model.

        SharedModelAssets shared;
        if (registry.registerModelOnce(leaf.modelRef())) {
            // Pass the short stem as both modelName and boneName to the stitcher.
            // The generic stitcher path uses those stems only for the entity atlas;
            // inventory icons are rendered separately below as compact 64x64 PNGs.
            // The original Java model ref is still preserved in modelAssetsCache
            // for cross-base-item asset reuse, and BedrockLog messages here include
            // leaf.modelRef() so the hash never becomes opaque during debugging.
            TextureStitcher.StitchResult stitch = TextureStitcher.stitchSingleModel(
                    modelHash, modelHash, resolved.mergedJson(),
                    mergedJavaPack, bedrockDir);
            if (isCancelled(cancellationRequested)) return false;
            if (stitch == null) {
                BedrockLog.warn("[BedrockConverter] Failed to stitch textures for generic 3D model "
                        + leaf.modelRef() + "; skipping");
                return false;
            }

            try {
                ExactTextureFileCache.CanonicalTexture canonical = exactAtlasCache.canonicalize(
                        stitch.atlasFile().toPath(), stitch.bedrockTexturePath());
                if (!canonical.packReference().equals(stitch.bedrockTexturePath())) {
                    stitch = new TextureStitcher.StitchResult(
                            canonical.file().toFile(), stitch.atlasWidth(), stitch.atlasHeight(),
                            stitch.spriteMap(), canonical.packReference(), stitch.bonePrimaryIconPath());
                }
            } catch (IOException exception) {
                // Keep the model-specific atlas on any cache failure. This is an
                // optimization only; conversion correctness must never depend on it.
                BedrockLog.debug("[BedrockConverter] Could not share exact atlas for "
                        + leaf.modelRef() + ": " + exception.getMessage());
            }

            String geometryIdentifier = "geometry." + BedrockShortName.BEDROCK_NAMESPACE + "." + modelHash;
            String geometryOutputPath = modelHash;
            String resultGeoId = FmmGeometryConverter.convertWithIdentifier(
                    geometryIdentifier, geometryOutputPath, resolved.mergedJson(),
                    stitch.spriteMap(), stitch.atlasWidth(), stitch.atlasHeight(),
                    bedrockDir);
            if (isCancelled(cancellationRequested)) return false;
            if (resultGeoId == null) {
                BedrockLog.warn("[BedrockConverter] Failed to convert geometry for generic 3D model "
                        + leaf.modelRef() + "; skipping");
                return false;
            }

            String animBaseId = BedrockShortName.BEDROCK_NAMESPACE + "." + modelHash;
            String animFileBase = modelHash;
            FmmAnimationGenerator.AnimationIds animIds = FmmAttachableGenerator.prepareAnimations(
                    animBaseId, animFileBase, resolved.mergedJson(), bedrockDir);
            if (isCancelled(cancellationRequested)) return false;
            if (animIds == null) {
                BedrockLog.warn("[BedrockConverter] Failed to prepare animations for generic 3D model "
                        + leaf.modelRef() + "; skipping");
                return false;
            }

            String iconRel = "textures/items/" + iconKey;
            File iconFile = new File(bedrockDir, iconRel + ".png");
            boolean rendered = IconRenderer.renderIcon(resolved.mergedJson(), mergedJavaPack, iconFile);
            if (isCancelled(cancellationRequested)) return false;
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
            if (isCancelled(cancellationRequested)) return false;
            String mappingHash = BedrockShortName.forBaseMapping(
                    leaf.modelRef(), base, MappedItemRegistry.predicateShape(leaf.predicates()));
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
            // Vanilla refs (minecraft:*) are expected misses — the client already ships
            // those textures, the merged pack doesn't — so keep them at debug. A miss on
            // a CUSTOM namespace (itemsadder/elitemobs/...) means a real custom item won't
            // render on Bedrock; surface the declared ref AND the exact path we looked for
            // so the operator can tell apart "texture absent from the pack" vs "referenced
            // under the wrong path."
            if ("minecraft".equals(ns)) {
                BedrockLog.debug("[BedrockConverter] Flat-icon texture not found (vanilla, not shipped): " + source.getPath());
            } else {
                BedrockLog.warn("[BedrockConverter] Missing texture for " + resolved.identifier()
                        + ": model declares '" + layer0 + "' but no file at " + source.getPath()
                        + "; skipping (item will not render on Bedrock)");
            }
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

    private static void generateItemTexture(Map<String, String> iconTextureMap, File bedrockDir)
            throws IOException {
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
        Files.createDirectories(outputFile.getParentFile().toPath());
        try (Writer writer = new BufferedWriter(new FileWriter(outputFile, StandardCharsets.UTF_8), 1 << 16)) {
            new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
                    .toJson(root, writer);
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

    /**
     * Hash of everything staged so far, used as the Bedrock pack's cache-bust token. The digest is
     * unchanged — same sorted order, same {@code path, 0, content, 0} framing, same hex output — but
     * the reads feeding it are overlapped across a bounded pool. On a real 10k-file staging tree the
     * serial version measured ~1.75 s, which made it the most expensive pass in the conversion.
     * See {@link PackFileIndex#sha256Hex}.
     */
    private static String contentDigest(File bedrockDir,
                                        BooleanSupplier cancellationRequested) {
        try {
            return PackFileIndex.sha256Hex(
                    PackFileIndex.sortedRegularFiles(bedrockDir.toPath()), cancellationRequested);
        } catch (CancellationException cancelled) {
            // Every caller re-checks cancellation right after, so this token is never published.
            return "";
        } catch (IOException e) {
            BedrockLog.warn("Failed to derive Bedrock pack content digest; falling back to timestamp cache bust: "
                    + e.getMessage());
            return String.valueOf(System.currentTimeMillis());
        }
    }

    private static void writeArtifactSetManifest(File bedrockDir,
                                                 String contentToken,
                                                 String pluginVersion,
                                                 File mappingsFile) throws IOException {
        boolean mappingsPresent = mappingsFile != null && mappingsFile.isFile();
        String mappingsSha1 = mappingsPresent
                ? Sha1.hex(mappingsFile).toLowerCase(Locale.ROOT)
                : null;
        long mappingsSize = mappingsPresent ? mappingsFile.length() : 0L;
        String generationSeed = contentToken + "\n"
                + (pluginVersion == null ? "" : pluginVersion) + "\n"
                + mappingsPresent + "\n"
                + (mappingsSha1 == null ? "" : mappingsSha1) + "\n"
                + mappingsSize;

        JsonObject manifest = new JsonObject();
        manifest.addProperty("formatVersion", ARTIFACT_SET_MANIFEST_VERSION);
        manifest.addProperty("generationId", UUID.nameUUIDFromBytes(
                generationSeed.getBytes(StandardCharsets.UTF_8)).toString());
        manifest.addProperty("mappingsPresent", mappingsPresent);
        if (mappingsPresent) {
            manifest.addProperty("mappingsSha1", mappingsSha1);
            manifest.addProperty("mappingsSize", mappingsSize);
        }

        File outputFile = new File(bedrockDir, ARTIFACT_SET_MANIFEST_NAME);
        try (Writer writer = new BufferedWriter(
                new FileWriter(outputFile, StandardCharsets.UTF_8), 4096)) {
            new com.google.gson.GsonBuilder().disableHtmlEscaping().create()
                    .toJson(manifest, writer);
        }
    }

    /** Legacy publications deliberately force one fresh conversion on reuse. */
    public static boolean hasArtifactSetManifest(File bedrockZip) {
        if (bedrockZip == null || !bedrockZip.isFile()) return false;
        try (ZipFile archive = new ZipFile(bedrockZip)) {
            var entry = archive.getEntry(ARTIFACT_SET_MANIFEST_NAME);
            return entry != null && !entry.isDirectory();
        } catch (IOException invalidArchive) {
            return false;
        }
    }

    /**
     * Frees the directory's path synchronously and reclaims its contents in the background,
     * falling back to an inline delete when the rename cannot be taken.
     */
    private static void deleteOffCriticalPath(File directory) {
        if (directory == null || !directory.exists()) return;
        if (AsyncDirectoryCleaner.deleteLater(directory)) return;
        recursivelyDelete(directory);
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

    private static void publishAtomically(Path pending, Path target) throws IOException {
        ZipUtil.publishAtomically(pending, target);
    }

    private static boolean isCancelled(BooleanSupplier cancellationRequested) {
        return Cancellation.isCancelled(cancellationRequested);
    }
}
