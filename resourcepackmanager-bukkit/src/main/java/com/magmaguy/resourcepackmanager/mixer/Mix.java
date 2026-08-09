package com.magmaguy.resourcepackmanager.mixer;

import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.utils.RSPLogger;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.api.ResourcePackManagerAPI;
import com.magmaguy.resourcepackmanager.autohost.AutoHost;
import com.magmaguy.resourcepackmanager.bedrock.BedrockConversion;
import com.magmaguy.resourcepackmanager.bedrock.BedrockOutputPublication;
import com.magmaguy.resourcepackmanager.bedrock.BukkitBedrockConverterContext;
import com.magmaguy.resourcepackmanager.config.DefaultConfig;
import com.magmaguy.resourcepackmanager.config.compatibleplugins.CompatiblePluginConfig;
import com.magmaguy.resourcepackmanager.config.compatibleplugins.CompatiblePluginConfigFields;
import com.magmaguy.resourcepackmanager.thirdparty.ThirdPartyResourcePack;
import com.magmaguy.resourcepackmanager.mixer.engine.MergeOperations;
import com.magmaguy.resourcepackmanager.mixer.engine.MixEngine;
import com.magmaguy.resourcepackmanager.mixer.engine.internal.AsyncDirectoryCleaner;
import com.magmaguy.resourcepackmanager.mixer.engine.internal.Cancellation;
import com.magmaguy.resourcepackmanager.mixer.engine.MixInput;
import com.magmaguy.resourcepackmanager.mixer.engine.MixOutput;
import com.magmaguy.resourcepackmanager.mixer.engine.MixerLogger;
import com.magmaguy.resourcepackmanager.mixer.engine.PackStagingException;
import com.magmaguy.resourcepackmanager.mixer.engine.internal.Sha1;
import lombok.Getter;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Comparator;
import java.util.function.BooleanSupplier;

/**
 * Bukkit-side orchestrator around the platform-neutral {@link MixEngine}.
 *
 * <p>Responsibilities kept on this side because they touch Bukkit-only state:
 * scheduling the work off the main thread, locating the plugin's data folder,
 * sorting the discovered {@link ThirdPartyResourcePack}s by configured priority,
 * resource-pack rerouting (driven by {@code DefaultConfig}), and the Bedrock
 * conversion step. The actual unzip / copy-with-collision-resolution / zip /
 * SHA-1 pipeline lives in {@code resourcepackmanager-mixer}.</p>
 */
public class Mix {
    private static final String resourcePackName = "ResourcePackManager_RSP";

    // Bridge MagmaCore's Logger into the platform-neutral MixerLogger interface so the
    // engine can warn/log without any Bukkit knowledge. The engine returns collisions
    // via MixOutput.collisionLog() rather than calling back into Mix's state.
    private static final MixerLogger BUKKIT_LOGGER = new MixerLogger() {
        @Override public void info(String m) { RSPLogger.detail(m); }
        @Override public void warn(String m) { Logger.warn(m); }
        @Override public void collision(String m) { /* collected via MixOutput */ }
    };

    // Reused by recursivelyCopyDirectory below (the cluster pre-merge path used by
    // ThirdPartyResourcePack). The mixer engine instantiates its own MergeOperations
    // internally; this one is independent so the two phases don't share mutable state.
    private static final MergeOperations CLUSTER_MERGE = new MergeOperations(BUKKIT_LOGGER);

    private static File mixerFolder;
    private static List<File> orderedFiles;

    @Getter
    private static File finalResourcePack;
    @Getter
    private static String finalSHA1;
    @Getter
    private static byte[] finalSHA1Bytes;
    private static final AtomicLong STARTUP_REUSE_GENERATION = new AtomicLong();

    private Mix() {
    }

    /**
     * Verifies and publishes the saved Java mix without waiting for monitored
     * plugins to finish their own initialization.
     *
     * <p>ResourcePackManager deliberately waits for its soft dependencies before
     * entering normal initialization. On a copied or restarted server that can
     * take minutes even though {@code mixer/}, the fingerprint sidecar, and the
     * merged ZIP are already complete. This startup-only path reads those
     * persisted mixer inputs directly. It never stages plugin sources and never
     * suppresses the normal watchdog, so a later source export still triggers the
     * regular stability check and remix.</p>
     */
    public static void publishVerifiedExistingMixAsync() {
        long generation = STARTUP_REUSE_GENERATION.incrementAndGet();
        new BukkitRunnable() {
            @Override
            public void run() {
                publishVerifiedExistingMix(() ->
                        generation != STARTUP_REUSE_GENERATION.get());
            }
        }.runTaskAsynchronously(ResourcePackManager.plugin);
    }

    /**
     * Cancels only the startup cache verification owned by the current enable
     * cycle. A new enable must not revive an old hash pass after /rspm reload.
     */
    public static void cancelStartupReuse() {
        STARTUP_REUSE_GENERATION.incrementAndGet();
    }

    static boolean publishVerifiedExistingMix(BooleanSupplier runCancellation) {
        BooleanSupplier cancellationRequested = () ->
                Thread.currentThread().isInterrupted()
                        || (runCancellation != null && runCancellation.getAsBoolean())
                        || shuttingDown();
        if (!initializeDefaultPluginFolders() || cancellationRequested.getAsBoolean()) return false;

        File outputFolder = getOutputFolder();
        if (!hasSavedMixMetadata(outputFolder)) return false;

        initializeThirdPartyResourcePacks();
        if (cancellationRequested.getAsBoolean()) return false;

        String inputFingerprint;
        try {
            inputFingerprint = calculateInputFingerprint(orderedFiles, cancellationRequested);
        } catch (CancellationException cancelled) {
            return false;
        }
        if (cancellationRequested.getAsBoolean()) return false;

        // Java delivery is independently safe once its exact bytes and all mixer
        // inputs validate. If Bedrock output is missing, the normal watchdog path
        // remains responsible for generating it after plugin sources settle.
        boolean reused = reuseExistingMix(inputFingerprint, outputFolder, false, cancellationRequested);
        if (reused) {
            RSPLogger.detail("Verified saved mixer inputs and merged ZIP; Java resource pack hosting started before monitored plugins finished initializing.");
        }
        return reused;
    }

    private static boolean hasSavedMixMetadata(File outputFolder) {
        File fingerprintFile = new File(outputFolder, MIX_FINGERPRINT_FILENAME);
        File mergedZip = new File(outputFolder, resourcePackName + ".zip");
        if (!fingerprintFile.isFile() || !mergedZip.isFile()) return false;
        try {
            List<String> lines = Files.readAllLines(fingerprintFile.toPath());
            return lines.size() >= 2
                    && lines.get(0).trim().length() == 40
                    && lines.get(1).trim().length() == 40;
        } catch (IOException exception) {
            return false;
        }
    }

    /**
     * Mixes resource packs for one concrete scheduler run.
     *
     * <p>The supplied token is deliberately run-scoped. A plugin-wide shutdown
     * flag is insufficient for /rspm reload because the following enable cycle
     * clears it while an old async conversion may still be unwinding.</p>
     *
     * @return {@code true} only when the existing outputs were reused or the
     *         complete requested mix (including Bedrock conversion) succeeded
     */
    public static boolean mixResourcePacks(BooleanSupplier runCancellation) {
        cancelStartupReuse();
        BooleanSupplier cancellationRequested = () ->
                Thread.currentThread().isInterrupted()
                        || (runCancellation != null && runCancellation.getAsBoolean())
                        || shuttingDown();
        RSPLogger.detail("Starting resource pack mixing...");
        if (!initializeDefaultPluginFolders()) return false;
        if (cancellationRequested.getAsBoolean()) return false;
        if (!ThirdPartyResourcePack.prepareResourcePacksForMix(cancellationRequested)) {
            if (cancellationRequested.getAsBoolean()) return false;
            Logger.warn("Resource pack staging failed; waiting for sources to settle before mixing.");
            ThirdPartyResourcePack.markResourcePackStagingFailed();
            return false;
        }
        if (cancellationRequested.getAsBoolean()) return false;
        initializeThirdPartyResourcePacks();
        if (cancellationRequested.getAsBoolean()) return false;

        File outputFolder = getOutputFolder();
        // Collision log lands in the plugin data folder root (not output/) — preserves the
        // legacy on-disk path so existing user scripts / docs continue to find it.
        File dataFolder = ResourcePackManager.plugin.getDataFolder();

        // Nothing about the inputs changed since the last mix, and last mix's outputs are still on
        // disk, so re-running the merge and the Bedrock conversion would burn ~15s to reproduce
        // byte-identical files. Reuse them and go straight to hosting.
        //
        // This matters most on reloads: /em reload finishes EliteMobs in ~2s, but every reload of
        // any monitored plugin re-triggers the whole pipeline, so the admin waited ~19s for work
        // whose own upload step then reported "remote server already has this resource pack".
        String inputFingerprint;
        try {
            inputFingerprint = calculateInputFingerprint(orderedFiles, cancellationRequested);
        } catch (CancellationException cancelled) {
            RSPLogger.detail("Resource pack mixing cancelled during input fingerprinting.");
            return false;
        }
        if (cancellationRequested.getAsBoolean()) return false;
        if (reuseExistingMix(inputFingerprint, outputFolder, true, cancellationRequested)) return true;

        MixInput input = new MixInput(
                orderedFiles,
                outputFolder,   // workingDir == outputDir matches legacy behaviour
                outputFolder,
                dataFolder,     // collisionLogDir
                resourcePackName,
                true
        );
        MixEngine engine = new MixEngine(BUKKIT_LOGGER, cancellationRequested);
        MixOutput out;
        try {
            out = engine.run(input);
        } catch (CancellationException cancelled) {
            RSPLogger.detail("Resource pack mixing cancelled.");
            return false;
        } catch (PackStagingException stagingFailure) {
            // One pack is unusable. Left unbounded this is a permanent wedge: the
            // failure is deterministic, the stability watcher re-arms after it, and
            // the merge never publishes. Count the failure against that specific pack
            // and let the quarantine drop it once retries are exhausted.
            Logger.warn("Mix engine failed: " + stagingFailure.getMessage());
            if (PackQuarantine.recordFailure(stagingFailure.getPack())) {
                // Re-arm now rather than waiting for an unrelated source change: the
                // next mix excludes this pack, so the merge that has been failing can
                // finally publish.
                ThirdPartyResourcePack.markResourcePackStagingFailed();
            }
            return false;
        } catch (IOException | RuntimeException e) {
            Logger.warn("Mix engine failed: " + e.getMessage());
            return false;
        }
        if (cancellationRequested.getAsBoolean()) return false;

        // The merged Java zip is complete the instant the engine returns: rerouting copies it into a
        // SEPARATE external folder and Bedrock conversion reads the unzipped mergedDir + writes
        // separate outputs — neither alters out.mergedZip(). So publish the Java pack and start
        // hosting NOW, before the (much slower, ~minute-long) Bedrock conversion runs. AutoHost.initialize()
        // schedules its work on an async task, so the Bedrock conversion below proceeds in parallel and
        // Java clients no longer wait on it.
        finalResourcePack = out.mergedZip();
        finalSHA1 = out.sha1Hex();
        finalSHA1Bytes = out.sha1Bytes();
        RSPLogger.detail("Java resource pack ready; starting hosting (Bedrock conversion continues in parallel).");
        AutoHost.initialize(cancellationRequested);

        // Reroute the merged zip (Bukkit-only because DefaultConfig is Bukkit-bound).
        applyResourcePackRerouting(out.mergedZip());

        // Always enter the converter, including when conversion is disabled: its
        // disabled/no-target paths authoritatively remove prior local and deployed
        // Bedrock output. Cancellation is checked before withdrawal so an interrupted
        // lifecycle retains the last-good publication.
        boolean bedrockConversionSucceeded = BedrockConversion.generate(
                out.mergedDir(),
                outputFolder,
                new BukkitBedrockConverterContext(cancellationRequested));
        if (cancellationRequested.getAsBoolean()) {
            // A cancellation observed after the converter's atomic marker move
            // is past the publication point of no return. Refresh consumers for
            // that committed set even though this scheduler run still unwinds.
            if (bedrockConversionSucceeded
                    && BedrockOutputPublication.current(outputFolder) != null) {
                AutoHost.publishBedrockOutputs();
            }
            return false;
        }
        if (!bedrockConversionSucceeded) {
            // A publication-stage failure can restore a still-authoritative
            // last-good set. Keep it available and retry the conversion later;
            // only withdraw when no verified authority remains.
            if (BedrockOutputPublication.current(outputFolder) != null) {
                AutoHost.publishBedrockOutputs();
                return false;
            }
            BedrockOutputPublication.withdraw(outputFolder);
            AutoHost.withdrawBedrockOutputs();
            return false;
        }

        File bedrockPack = new File(outputFolder, BedrockConversion.BEDROCK_PACK_NAME + ".zip");
        if (bedrockPack.isFile()) {
            // BedrockConversion committed the authority marker while it still
            // retained rollback snapshots. Publish that verified set to HTTP,
            // Geyser and relay consumers now.
            AutoHost.publishBedrockOutputs();
        } else {
            BedrockOutputPublication.withdraw(outputFolder);
            AutoHost.withdrawBedrockOutputs();
        }
        if (cancellationRequested.getAsBoolean()) return false;

        // Final cleanup: the engine already deleted per-pack staging dirs but left the
        // merged unzipped folder so we could run rerouting + Bedrock conversion against
        // it. Now it's safe to delete. Bedrock outputs (zip + Geyser mappings) survive
        // because they live alongside, not inside, the merged folder.
        if (out.mergedDir().exists()) {
            // Reclaiming the merged tree is pure disk housekeeping — nothing downstream reads it
            // again. Hand it to the background cleaner so /rspm reload returns as soon as the pack
            // is published instead of after ~25k unlink calls.
            if (!AsyncDirectoryCleaner.deleteLater(out.mergedDir())) {
                recursivelyDeleteDirectory(out.mergedDir());
            }
        }

        writeMixFingerprint(outputFolder, inputFingerprint, out.sha1Hex());
        // Retires failure counts that never reached the quarantine threshold, so a
        // transient failure now and another one hours later are not treated as a
        // streak. Standing quarantines survive by design.
        PackQuarantine.noteSuccessfulMix();
        RSPLogger.detail("Resource pack mixing complete.");
        return true;
    }

    private static final String MIX_FINGERPRINT_FILENAME = ".rspm_mix_fingerprint";

    /**
     * Fingerprints everything that can change the merged Java pack: the ordered list of source
     * packs (name + size + content hash), and the settings that alter its bytes. Bedrock conversion
     * is deliberately not part of this key: it is a separate output derived after the Java mix.
     * {@link #reuseExistingMix(String, File, boolean)} independently requires that output when conversion
     * is enabled, so changing false to true still fails closed when the Bedrock pack is absent.
     * <p>
     * Order matters — it drives collision resolution — so the list is hashed in sequence rather
     * than as a set.
     *
     * @return the fingerprint, or null if it could not be computed (which forces a full mix)
     */
    static String calculateInputFingerprint(List<File> inputs,
                                            BooleanSupplier cancellationRequested) {
        if (inputs == null) return null;
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-1");
            digest.update(("packName=" + resourcePackName).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            for (File input : inputs) {
                throwIfCancelled(cancellationRequested);
                if (input == null || !input.exists()) return null;
                digest.update(input.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update(Long.toString(input.length()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (input.isDirectory()) {
                    //Cluster sources are directories; hash the tree, sorted so the walk order
                    //cannot make an unchanged tree look changed.
                    Path root = input.toPath();
                    List<Path> children;
                    try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
                        children = walk.filter(Files::isRegularFile)
                                .sorted(Comparator.comparing(child -> fingerprintRelativePath(root, child)))
                                .toList();
                    }
                    for (Path child : children) {
                        throwIfCancelled(cancellationRequested);
                        digest.update(fingerprintRelativePath(root, child)
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        digest.update(Long.toString(Files.size(child)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        updateDigest(child, digest, cancellationRequested);
                    }
                } else {
                    updateDigest(input.toPath(), digest, cancellationRequested);
                }
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (Exception exception) {
            Logger.warn("Could not fingerprint the resource pack inputs (" + exception.getMessage() + "); mixing from scratch.");
            return null;
        }
    }

    private static String fingerprintRelativePath(Path root, Path child) {
        return root.relativize(child).toString().replace('\\', '/');
    }

    private static void updateDigest(Path file,
                                     java.security.MessageDigest digest,
                                     BooleanSupplier cancellationRequested) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = Files.newInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                throwIfCancelled(cancellationRequested);
                digest.update(buffer, 0, read);
            }
        }
    }

    private static void throwIfCancelled(BooleanSupplier cancellationRequested) {
        Cancellation.check(cancellationRequested, "Resource pack mix cancelled");
    }

    /**
     * Republishes the previous mix when the inputs are unchanged and its outputs are all still
     * present. Deliberately conservative: any missing output, any unreadable fingerprint, or any
     * doubt at all falls through to a full mix.
     *
     * @return true when the previous outputs were reused and the pipeline can stop here
     */
    private static boolean reuseExistingMix(String inputFingerprint,
                                             File outputFolder,
                                             boolean requireBedrockOutput,
                                             BooleanSupplier cancellationRequested) {
        if (inputFingerprint == null) return false;
        BooleanSupplier cancelled = cancellationRequested == null ? () -> false : cancellationRequested;
        if (cancelled.getAsBoolean()) return false;

        File fingerprintFile = new File(outputFolder, MIX_FINGERPRINT_FILENAME);
        if (!fingerprintFile.isFile()) return false;

        File mergedZip = new File(outputFolder, resourcePackName + ".zip");
        if (!mergedZip.isFile()) return false;

        //Line 1 is the input fingerprint, line 2 the SHA-1 the mix engine reported for the zip.
        String recordedSha1;
        try {
            List<String> lines = Files.readAllLines(fingerprintFile.toPath());
            if (lines.size() < 2) return false;
            if (!inputFingerprint.equals(lines.get(0).trim())) return false;
            recordedSha1 = lines.get(1).trim();
        } catch (IOException exception) {
            return false;
        }

        //Bedrock outputs are produced by a separate step, so an enabled-but-missing Bedrock pack
        //has to force the full path even when the Java side is intact.
        boolean bedrockEnabled = DefaultConfig.isBedrockConversionEnabled();
        BedrockOutputPublication.Snapshot bedrockPublication = bedrockEnabled
                ? BedrockOutputPublication.current(outputFolder)
                : null;
        if (bedrockPublication != null
                && !BedrockConversion.hasArtifactSetManifest(
                bedrockPublication.pack())) {
            bedrockPublication = null;
        }
        if (cancelled.getAsBoolean()) return false;
        if (requireBedrockOutput && bedrockEnabled && bedrockPublication == null) return false;

        String storedSha1 = recordedSha1;
        if (storedSha1 == null || storedSha1.length() != 40) return false;
        // ZipUtil's streaming digest wraps the complete archive output stream, so
        // it is exactly the digest of the finished bytes. Verify those bytes before
        // trusting the sidecar: otherwise a truncated/replaced ZIP with a valid old
        // fingerprint was republished to every player under the old hash.
        try {
            String actualSha1 = Sha1.hex(mergedZip);
            if (cancelled.getAsBoolean()) return false;
            if (!storedSha1.equalsIgnoreCase(actualSha1)) return false;
        } catch (IOException exception) {
            return false;
        }

        byte[] sha1Bytes = new byte[20];
        try {
            for (int i = 0; i < 20; i++)
                sha1Bytes[i] = (byte) Integer.parseInt(storedSha1.substring(i * 2, i * 2 + 2), 16);
        } catch (NumberFormatException exception) {
            return false;
        }
        if (cancelled.getAsBoolean()) return false;

        finalResourcePack = mergedZip;
        finalSHA1 = storedSha1;
        finalSHA1Bytes = sha1Bytes;

        RSPLogger.detail("Resource pack sources are unchanged since the last mix; reusing the existing pack.");
        // Reuse skips conversion, but deployment configuration may still have
        // changed since the last run (including true -> false or Geyser A -> B).
        // Converge the retained exact target before republishing the old set.
        BedrockConversion.deployPreviousMappingsIfNeeded(
                new BukkitBedrockConverterContext(cancelled));
        AutoHost.initialize(cancelled);
        if (cancelled.getAsBoolean()) return false;
        applyResourcePackRerouting(mergedZip);
        if (bedrockEnabled && bedrockPublication != null) {
            AutoHost.publishBedrockOutputs();
        } else if (!bedrockEnabled) {
            BedrockOutputPublication.withdraw(outputFolder);
            AutoHost.withdrawBedrockOutputs();
        }
        return true;
    }

    private static void writeMixFingerprint(File outputFolder, String inputFingerprint, String sha1Hex) {
        if (inputFingerprint == null || sha1Hex == null) return;
        try {
            Files.writeString(new File(outputFolder, MIX_FINGERPRINT_FILENAME).toPath(),
                    inputFingerprint + System.lineSeparator() + sha1Hex);
        } catch (IOException exception) {
            //Only costs a redundant mix next boot.
            Logger.warn("Could not record the resource pack fingerprint: " + exception.getMessage());
        }
    }

    // Bail out of the mix pipeline as soon as the plugin disables, so Bukkit
    // doesn't nag about un-shutdown async tasks. Each phase below can take
    // multiple seconds (file I/O on potentially many resource packs).
    private static boolean shuttingDown() {
        return com.magmaguy.magmacore.MagmaCore.isShutdownRequested(ResourcePackManager.plugin);
    }

    private static boolean initializeDefaultPluginFolders() {
        try {
            mixerFolder = new File(ResourcePackManager.plugin.getDataFolder().getAbsolutePath() + File.separatorChar + "mixer");
            if (!mixerFolder.exists()) mixerFolder.mkdir();

            File outputFolder = getOutputFolder();
            if (!outputFolder.exists()) outputFolder.mkdir();

            return true;
        } catch (Exception e) {
            Logger.warn("Failed to create default plugin folders! Check the OS permissions on the plugin's configuration folder.");
            return false;
        }
    }

    /**
     * Builds the ordered list of resource packs to merge, sorted by configured priority.
     * Packs are copied in this order during merging — higher priority packs go first,
     * and their files are preserved when lower priority packs collide with them.
     *
     * <p>Cluster-style raw directories in the mixer folder are appended strictly LAST
     * (after the priority-sorted zips), preserving legacy {@code copyClusterDirectoriesToOutput}
     * ordering. Lumping them into the same priority bucket as un-prioritized zips would
     * leave the relative order to {@code HashMap} iteration, which is JVM-undefined.</p>
     */
    private static void initializeThirdPartyResourcePacks() {
        List<String> priorityOrder = DefaultConfig.getPriorityOrder();
        if (priorityOrder == null) priorityOrder = List.of();

        // Collect all enabled packs from both the static set and API registrations
        Set<ThirdPartyResourcePack> uniquePacks = new LinkedHashSet<>(ThirdPartyResourcePack.thirdPartyResourcePacks);
        uniquePacks.addAll(ResourcePackManagerAPI.thirdPartyResourcePackHashMap.values());
        List<ThirdPartyResourcePack> allPacks = new ArrayList<>(uniquePacks);

        // Build a unified map of (file -> priority) for sorting — ZIP/file inputs only.
        Map<File, Integer> filePriorities = new HashMap<>();
        Set<String> registeredFilenames = new HashSet<>();
        Map<String, Integer> configuredMixerPriorities = new HashMap<>();
        Set<String> configuredExcludedEntries = new HashSet<>();

        Map<String, CompatiblePluginConfigFields> compatiblePlugins =
                CompatiblePluginConfig.getCompatiblePlugins();
        if (compatiblePlugins != null) {
            for (CompatiblePluginConfigFields fields : compatiblePlugins.values()) {
                if (fields == null || fields.getPluginName() == null
                        || fields.getPluginName().isBlank()) continue;
                String primary = fields.getPluginName() + "_resource_pack.zip";
                String shared = fields.getPluginName() + "_shared_resource_pack.zip";
                if (!fields.isEnabled()) {
                    configuredExcludedEntries.add(primary.toLowerCase(java.util.Locale.ROOT));
                    configuredExcludedEntries.add(shared.toLowerCase(java.util.Locale.ROOT));
                    configuredExcludedEntries.add((fields.getPluginName() + "_cluster_temp")
                            .toLowerCase(java.util.Locale.ROOT));
                    configuredExcludedEntries.add((fields.getPluginName() + "_shared_cluster_temp")
                            .toLowerCase(java.util.Locale.ROOT));
                    continue;
                }
                int priority = priorityOrder.indexOf(fields.getPluginName());
                int normalizedPriority = priority >= 0 ? priority : Integer.MAX_VALUE;
                configuredMixerPriorities.put(
                        primary.toLowerCase(java.util.Locale.ROOT), normalizedPriority);
                configuredMixerPriorities.put(
                        shared.toLowerCase(java.util.Locale.ROOT), normalizedPriority);
            }
        }

        for (ThirdPartyResourcePack pack : allPacks) {
            if (!pack.isEnabled()
                    || ThirdPartyResourcePack.isConfigurationExcludedPlugin(pack.getPluginName())) continue;
            File mixerInput = pack.getMixerResourcePack();
            if (mixerInput == null) {
                File persistedInput = new File(mixerFolder, pack.getMixerFilename());
                if (persistedInput.isFile()) mixerInput = persistedInput;
            }
            if (mixerInput == null) continue;
            registeredFilenames.add(mixerInput.getName());
            filePriorities.put(mixerInput,
                    pack.getPriority() >= 0 ? pack.getPriority() : Integer.MAX_VALUE);
        }

        // Cluster directories are kept in a separate ordered list so we can append them
        // strictly after the sorted zips — matches legacy "clusters always come last".
        List<File> clusterDirs = new ArrayList<>();

        // Add custom zip files from the mixer folder (user-provided packs not tied to a plugin)
        File[] mixerContents = mixerFolder.listFiles();
        if (mixerContents != null) {
            for (File file : mixerContents) {
                if (file.isDirectory() || !file.getName().endsWith(".zip")) continue;
                if (ThirdPartyResourcePack.isConfigurationExcludedMixerEntry(file.getName())) continue;
                if (configuredExcludedEntries.contains(
                        file.getName().toLowerCase(java.util.Locale.ROOT))) continue;
                if (registeredFilenames.contains(file.getName())) continue;
                Integer configuredPackPriority = configuredMixerPriorities.get(
                        file.getName().toLowerCase(java.util.Locale.ROOT));
                int prio = configuredPackPriority != null
                        ? configuredPackPriority
                        : configuredPriority(file, priorityOrder);
                filePriorities.put(file, prio >= 0 ? prio : Integer.MAX_VALUE);
            }

            // Surface cluster-style directories the same way the legacy pipeline did:
            // the engine handles directory inputs natively (staging them under a
            // cluster_<dirname> wrapper), so we just forward them in priority order.
            for (File file : mixerContents) {
                if (!file.isDirectory()) continue;
                if (file.getName().equals("output")) continue;
                if (ThirdPartyResourcePack.isConfigurationExcludedMixerEntry(file.getName())) continue;
                if (configuredExcludedEntries.contains(
                        file.getName().toLowerCase(java.util.Locale.ROOT))) continue;
                clusterDirs.add(file);
            }
        }

        // Sort prioritized files (lower index = higher priority = copied first = wins collisions),
        // then append clusters in deterministic listFiles() order — they're always last so explicit
        // plugin-registered packs win collisions against them, exactly like the legacy pipeline.
        List<File> result = new ArrayList<>(filePriorities.size() + clusterDirs.size());
        filePriorities.entrySet().stream()
                .sorted(Comparator
                        .comparingInt((Map.Entry<File, Integer> entry) -> entry.getValue())
                        .thenComparing(entry -> stableFileOrder(entry.getKey())))
                .forEach(entry -> result.add(entry.getKey()));
        clusterDirs.sort(Comparator.comparing(Mix::stableFileOrder));
        result.addAll(clusterDirs);
        // Filtered here, at the single point where the input list is finalized, so every
        // downstream consumer — fingerprint, reuse check, engine — agrees on the set that
        // will actually be merged.
        orderedFiles = PackQuarantine.excludeQuarantined(result);
    }

    private static int configuredPriority(File file, List<String> priorityOrder) {
        int priority = priorityOrder.indexOf(file.getName());
        if (priority >= 0) return priority;

        String baseName = file.getName().substring(0, file.getName().length() - ".zip".length());
        priority = priorityOrder.indexOf(baseName);
        if (priority >= 0) return priority;

        String generatedName = file.getName();
        String sharedSuffix = "_shared_resource_pack.zip";
        String primarySuffix = "_resource_pack.zip";
        if (generatedName.endsWith(sharedSuffix)) {
            return priorityOrder.indexOf(
                    generatedName.substring(0, generatedName.length() - sharedSuffix.length()));
        }
        if (generatedName.endsWith(primarySuffix)) {
            return priorityOrder.indexOf(
                    generatedName.substring(0, generatedName.length() - primarySuffix.length()));
        }
        return -1;
    }

    private static String stableFileOrder(File file) {
        return file.toPath().toAbsolutePath().normalize().toString()
                .replace('\\', '/')
                .toLowerCase(java.util.Locale.ROOT);
    }

    private static void applyResourcePackRerouting(File mergedZip) {
        String rerouteTarget = DefaultConfig.getResourcePackRerouting();
        if (rerouteTarget == null || rerouteTarget.isEmpty() || rerouteTarget.isBlank()) return;
        try {
            File rerouteFolder = new File(ResourcePackManager.plugin.getDataFolder().getParentFile().getAbsolutePath() + File.separatorChar + rerouteTarget);
            if (!rerouteFolder.exists()) {
                Logger.warn("Failed to reroute zipped file to " + rerouteFolder.getAbsolutePath() + " because that folder does not exist!");
                return;
            }
            if (!rerouteFolder.isDirectory()) {
                Logger.warn("Failed to reroute zipped file to " + rerouteFolder.getAbsolutePath() + " because that is a file and not a folder!");
                return;
            }
            // The engine has already compressed and hashed the final Java-client
            // archive. Re-compressing the staging tree here doubles the expensive
            // ZIP pass and can drift from engine-side output filters. Rerouting is
            // transport only, so copy the exact published bytes instead.
            copyResourcePackToReroute(mergedZip, rerouteFolder);
        } catch (Exception e) {
            Logger.warn("Failed to reroute zipped file to " + DefaultConfig.getResourcePackRerouting());
        }
    }

    static void copyResourcePackToReroute(File mergedZip, File rerouteFolder) throws IOException {
        Path reroutedZip = rerouteFolder.toPath().resolve(resourcePackName + ".zip");
        if (mergedZip.toPath().toAbsolutePath().normalize()
                .equals(reroutedZip.toAbsolutePath().normalize())) {
            return;
        }
        Files.copy(mergedZip.toPath(), reroutedZip, StandardCopyOption.REPLACE_EXISTING);
    }

    private static File getOutputFolder() {
        return new File(ResourcePackManager.plugin.getDataFolder().getAbsolutePath() + File.separatorChar + "output");
    }

    /**
     * Recursively delete a directory tree. Kept as a public static helper because
     * {@code ThirdPartyResourcePack} uses it when assembling cluster temp folders
     * — extracting it into {@code resourcepackmanager-mixer} would force {@code ThirdPartyResourcePack}
     * (a Bukkit-side class) to depend on the mixer internal API.
     */
    public static void recursivelyDeleteDirectory(File directory) {
        if (directory.isDirectory()) {
            File[] children = directory.listFiles();
            if (children != null) {
                for (File file : children) {
                    recursivelyDeleteDirectory(file);
                }
            }
            try {
                Files.delete(directory.toPath());
            } catch (Exception e) {
                Logger.warn("Failed to delete directory " + directory.getPath());
            }
        } else {
            try {
                Files.delete(directory.toPath());
            } catch (IOException e) {
                Logger.warn("Failed to delete file " + directory.getPath());
            }
        }
    }

    /**
     * Recursively copy a directory tree with the same collision-resolution rules
     * the engine uses (pack.mcmeta merge, JSON deep-merge for mergeable files,
     * higher-priority wins for everything else). Used by
     * {@code ThirdPartyResourcePack.processCluster} to combine multiple cluster
     * sub-packs into a single temp directory before zipping it as one pack input
     * for the engine. Kept public for the same callsite reason — moving it into
     * {@code resourcepackmanager-mixer} would force the Bukkit-side cluster code to import the
     * mixer's internal helpers.
     */
    public static void recursivelyCopyDirectory(File source, File target) {
        if (source.isDirectory()) {
            String sourceName = source.getName();
            File nestedTarget = new File(target.getAbsolutePath() + File.separatorChar + sourceName);
            nestedTarget.mkdir();
            File[] children = source.listFiles();
            if (children != null) {
                for (File file : children) {
                    recursivelyCopyDirectory(file, nestedTarget);
                }
            }
        } else {
            try {
                Path targetPath = Path.of(target.getPath() + File.separatorChar + source.getName());
                if (targetPath.toFile().exists()) {
                    // Collision during cluster pre-merging — same rules as the
                    // engine's resolveFileCollision, but with no collision-log
                    // recording (matches legacy behaviour where cluster-stage
                    // collisions were silently dropped because collisionLog was
                    // still null at this point in the lifecycle).
                    CLUSTER_MERGE.mergeColliding(source, targetPath.toFile());
                    return;
                }
                Path parent = targetPath.getParent();
                if (parent != null) parent.toFile().mkdirs();
                Files.copy(source.toPath(), targetPath);
            } catch (IOException e) {
                Logger.warn("Failed to copy file");
                throw new RuntimeException(e);
            }
        }
    }

}
