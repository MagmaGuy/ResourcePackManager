package com.magmaguy.resourcepackmanager.thirdparty;

import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.utils.RSPLogger;
import com.magmaguy.magmacore.util.ZipFile;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.mixer.Mix;
import com.magmaguy.resourcepackmanager.config.DefaultConfig;
import com.magmaguy.resourcepackmanager.config.compatibleplugins.CompatiblePluginConfigFields;
import com.magmaguy.resourcepackmanager.mixer.engine.internal.Sha1;
import lombok.Getter;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpEntity;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

public class ThirdPartyResourcePack {
    public static Set<ThirdPartyResourcePack> thirdPartyResourcePacks = ConcurrentHashMap.newKeySet();
    private static final Set<String> configurationExcludedPluginNames = ConcurrentHashMap.newKeySet();
    private static final Set<String> configurationExcludedMixerEntries = ConcurrentHashMap.newKeySet();

    @Getter
    private final String pluginName;
    @Getter
    private final String mixerFilename;
    private final String localPath;
    private final String url;
    private File file = null;
    private boolean zips;
    private boolean cluster;
    private String reloadCommand;
    @Getter
    private boolean isEnabled;
    private String SHA1;
    @Getter
    private File mixerResourcePack = null;
    @Getter
    private int priority = -1;
    @Getter
    private boolean done = false;

    private static volatile boolean mixInProgress = false;
    private static final Object MIX_COMPLETION_MONITOR = new Object();
    private static volatile MixCancellation activeMixCancellation = null;
    private static volatile long watchdogGeneration = 0;
    private int ticksWithoutChange = 0;
    private boolean consideredStable = false;
    private boolean stableResourcePackSent = false;

    public ThirdPartyResourcePack(String pluginName, String localPath, String url, boolean zips, boolean cluster, String reloadCommand) {
        this(pluginName, localPath, url, zips, cluster, reloadCommand, "");
    }

    public ThirdPartyResourcePack(String pluginName, String localPath, String url, boolean zips, boolean cluster, String reloadCommand, String mixerFilenameSuffix) {
        this.pluginName = pluginName;
        this.mixerFilename = mixerFilename(pluginName, mixerFilenameSuffix);
        this.url = url;
        this.localPath = localPath;

        if (isConfigurationExcludedPlugin(pluginName)) {
            isEnabled = false;
            done = true;
            return;
        }

        isEnabled = Bukkit.getPluginManager().isPluginEnabled(pluginName);
        if (!isEnabled) {
            done = true;
            return;
        }

        if (localPath != null && !processLocal(localPath)) {
            done = true;
            return;
        } else if (localPath == null && url == null) {
            Logger.warn("Plugin " + pluginName + " has no resource pack path specified! ResourcePackManager will not be able to merge the resource pack from this plugin.");
            isEnabled = false;
            done = true;
            return;
        }

        this.reloadCommand = reloadCommand;
        this.zips = zips;
        this.cluster = cluster;

        if (localPath != null) SHA1 = getSourceFingerprint();

        // Check if source file matches existing mixer file - if so, no changes occurred and it's stable
        if (!cluster && zips && localPath != null) {
            File existingMixerFile = getTarget().toFile();
            if (existingMixerFile.exists()) {
                String existingMixerSHA1 = getSHA1(existingMixerFile);
                if (Objects.equals(SHA1, existingMixerSHA1)) {
                    consideredStable = true;
                }
            }
        }
        if (localPath == null && url != null) {
            consideredStable = true;
        }

        if (DefaultConfig.getPriorityOrder().contains(pluginName))
            priority = DefaultConfig.getPriorityOrder().indexOf(pluginName);

        thirdPartyResourcePacks.add(this);
        done = true;
    }

    private static BukkitTask resourcePackChangeWatcher = null;

    /**
     * Checks whether a monitored plugin has finished its Magmacore initialization.
     * Uses System properties published by each plugin's shaded Magmacore instance.
     */
    private static boolean isPluginInitialized(String pluginName) {
        String state = System.getProperty("magmacore.init." + pluginName);
        // If no state is published, the plugin doesn't use Magmacore — treat as ready.
        // Across shaded MagmaCore copies, the useful blocking state is INITIALIZING;
        // FAILED/UNINITIALIZED should not deadlock RSPM forever.
        if (state == null) return true;
        return !"INITIALIZING".equals(state);
    }

    public static void startResourcePackChangeWatchdog() {
        if (resourcePackChangeWatcher != null) {
            resourcePackChangeWatcher.cancel();
        }
        long generation;
        synchronized (MIX_COMPLETION_MONITOR) {
            generation = ++watchdogGeneration;
        }
        resourcePackChangeWatcher = new BukkitRunnable() {
            private boolean allPluginsReady = false;

            @Override
            public void run() {
                if (generation != watchdogGeneration) return;
                // Phase 1: Wait for all monitored plugins to finish initializing
                if (!allPluginsReady) {
                    for (ThirdPartyResourcePack thirdPartyResourcePack : thirdPartyResourcePacks) {
                        if (!thirdPartyResourcePack.isEnabled) continue;
                        if (!isPluginInitialized(thirdPartyResourcePack.pluginName)) {
                            return; // Still waiting — check again next tick
                        }
                    }
                    allPluginsReady = true;
                    RSPLogger.detail("All monitored plugins are initialized. Starting resource pack stability checks.");
                }

                // Check if any monitored plugin has gone back to initializing (reload detected)
                for (ThirdPartyResourcePack thirdPartyResourcePack : thirdPartyResourcePacks) {
                    if (!thirdPartyResourcePack.isEnabled) continue;
                    if (!isPluginInitialized(thirdPartyResourcePack.pluginName)) {
                        RSPLogger.detail("Plugin " + thirdPartyResourcePack.pluginName + " is reloading. Pausing resource pack processing.");
                        allPluginsReady = false;
                        // Reset stability for all packs since a reload may change them
                        for (ThirdPartyResourcePack pack : thirdPartyResourcePacks) {
                            pack.consideredStable = false;
                            pack.stableResourcePackSent = false;
                            pack.ticksWithoutChange = 0;
                        }
                        return;
                    }
                }

                // Phase 2: SHA1 stability checks (same logic, but no arbitrary extra delay)
                boolean readyToSend = true;
                boolean stableAlreadySent = true;
                for (ThirdPartyResourcePack thirdPartyResourcePack : thirdPartyResourcePacks) {
                    if (!thirdPartyResourcePack.isEnabled) continue;
                    String sourceFingerprint = thirdPartyResourcePack.getSourceFingerprint();
                    if (sourceFingerprint == null) {
                        readyToSend = false;
                        continue;
                    }
                    if (!Objects.equals(sourceFingerprint, thirdPartyResourcePack.SHA1)) {
                        thirdPartyResourcePack.ticksWithoutChange = 0;
                        thirdPartyResourcePack.SHA1 = sourceFingerprint;
                        if (thirdPartyResourcePack.consideredStable) {
                            thirdPartyResourcePack.consideredStable = false;
                            thirdPartyResourcePack.stableResourcePackSent = false;
                            RSPLogger.detail("Resource pack for " + thirdPartyResourcePack.pluginName + " has changed, considering it unstable.");
                        }
                    }
                    if (!thirdPartyResourcePack.stableResourcePackSent) stableAlreadySent = false;
                    if (!thirdPartyResourcePack.consideredStable) readyToSend = false;
                    if (thirdPartyResourcePack.consideredStable) continue;
                    thirdPartyResourcePack.ticksWithoutChange++;
                    if (thirdPartyResourcePack.ticksWithoutChange == 3) {
                        thirdPartyResourcePack.consideredStable = true;
                        RSPLogger.detail("Resource pack for " + thirdPartyResourcePack.pluginName + " has not changed for 3 seconds, considering it stable.");
                    }
                }

                if (!stableAlreadySent && readyToSend && !mixInProgress) {
                    MixCancellation cancellation = new MixCancellation();
                    synchronized (MIX_COMPLETION_MONITOR) {
                        if (generation != watchdogGeneration || mixInProgress) return;
                        mixInProgress = true;
                        activeMixCancellation = cancellation;
                    }
                    notifyResourcePackSending();
                    tagAsResourcePackSent();
                    RSPLogger.detail("Sending resource pack now.");
                    try {
                        Bukkit.getScheduler().runTaskAsynchronously(ResourcePackManager.plugin, () -> {
                            boolean mixSucceeded = false;
                            try {
                                mixSucceeded = Mix.mixResourcePacks(cancellation);
                            } catch (RuntimeException mixFailure) {
                                Logger.warn("Resource pack mixing failed unexpectedly: "
                                        + mixFailure.getMessage());
                            } finally {
                                synchronized (MIX_COMPLETION_MONITOR) {
                                    if (activeMixCancellation == cancellation) {
                                        if (!mixSucceeded
                                                && !isMixRunCancelled(cancellation, generation)) {
                                            rearmResourcePackSending();
                                        }
                                        activeMixCancellation = null;
                                        mixInProgress = false;
                                    }
                                    MIX_COMPLETION_MONITOR.notifyAll();
                                }
                            }
                        });
                    } catch (RuntimeException schedulingFailure) {
                        synchronized (MIX_COMPLETION_MONITOR) {
                            if (activeMixCancellation == cancellation) {
                                if (!isMixRunCancelled(cancellation, generation)) {
                                    rearmResourcePackSending();
                                    Logger.warn("Could not schedule resource pack mixing; it will retry: "
                                            + schedulingFailure.getMessage());
                                }
                                activeMixCancellation = null;
                                mixInProgress = false;
                            }
                            MIX_COMPLETION_MONITOR.notifyAll();
                        }
                    }
                }
            }
        }.runTaskTimerAsynchronously(ResourcePackManager.plugin, 20, 20);
    }

    public static void tagAsResourcePackSent(){
        for (ThirdPartyResourcePack thirdPartyResourcePack : thirdPartyResourcePacks) {
            thirdPartyResourcePack.stableResourcePackSent = true;
        }
    }

    public static void rearmResourcePackSending() {
        for (ThirdPartyResourcePack thirdPartyResourcePack : thirdPartyResourcePacks) {
            thirdPartyResourcePack.stableResourcePackSent = false;
        }
    }

    public static void markResourcePackStagingFailed() {
        for (ThirdPartyResourcePack thirdPartyResourcePack : thirdPartyResourcePacks) {
            thirdPartyResourcePack.stableResourcePackSent = false;
            thirdPartyResourcePack.consideredStable = false;
            thirdPartyResourcePack.ticksWithoutChange = 0;
        }
    }

    public static boolean prepareResourcePacksForMix(BooleanSupplier cancellationRequested) {
        boolean allPrepared = true;
        for (ThirdPartyResourcePack thirdPartyResourcePack : thirdPartyResourcePacks) {
            if (cancellationRequested != null && cancellationRequested.getAsBoolean()) return false;
            if (!thirdPartyResourcePack.isEnabled) continue;
            if (!thirdPartyResourcePack.stageLatestSource()) {
                allPrepared = false;
            }
        }
        return allPrepared;
    }

    private static void notifyResourcePackSending() {
        String message = "&eAll resource packs are stable. Mixing and sending now.";
        RSPLogger.detail("All resource packs are stable. Mixing and sending now.");
        Runnable notifyOperators = () -> {
            for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
                if (player.isOp()) {
                    player.sendMessage(com.magmaguy.magmacore.util.ChatColorConverter.convert(message));
                }
            }
        };
        if (Bukkit.isPrimaryThread()) {
            notifyOperators.run();
        } else {
            Bukkit.getScheduler().runTask(ResourcePackManager.plugin, notifyOperators);
        }
    }

    public static void shutdown() {
        if (resourcePackChangeWatcher != null) {
            resourcePackChangeWatcher.cancel();
            resourcePackChangeWatcher = null;
        }
        synchronized (MIX_COMPLETION_MONITOR) {
            watchdogGeneration++;
            MixCancellation cancellation = activeMixCancellation;
            if (cancellation != null) cancellation.cancel();
        }
        waitForActiveMix();
        thirdPartyResourcePacks.clear();
        configurationExcludedPluginNames.clear();
        configurationExcludedMixerEntries.clear();
        // A reload is an explicit "try again" from the operator, so quarantines start
        // over rather than persisting across the cycle that was meant to clear them.
        com.magmaguy.resourcepackmanager.mixer.PackQuarantine.reset();
    }

    private static void waitForActiveMix() {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        synchronized (MIX_COMPLETION_MONITOR) {
            while (mixInProgress) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    Logger.warn("Timed out waiting for resource pack mixing to stop during shutdown.");
                    return;
                }
                try {
                    java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(
                            MIX_COMPLETION_MONITOR,
                            remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static boolean isMixRunCancelled(MixCancellation cancellation, long generation) {
        return cancellation.getAsBoolean()
                || generation != watchdogGeneration
                || com.magmaguy.magmacore.MagmaCore.isShutdownRequested(ResourcePackManager.plugin);
    }

    /**
     * Cancellation belongs to one concrete mix run. It must never be represented
     * solely by MagmaCore's plugin-wide shutdown flag because /rspm reload clears
     * that flag for the new enable cycle; an old conversion that outlives disable
     * would then resume and race the new one.
     */
    static final class MixCancellation implements BooleanSupplier {
        private final AtomicBoolean cancellationRequested = new AtomicBoolean();

        void cancel() {
            cancellationRequested.set(true);
        }

        @Override
        public boolean getAsBoolean() {
            return cancellationRequested.get() || Thread.currentThread().isInterrupted();
        }
    }

    public static void initializeThirdPartyResourcePack(CompatiblePluginConfigFields compatiblePluginConfigFields) {
        if (!compatiblePluginConfigFields.isEnabled()) {
            excludeCompatiblePlugin(compatiblePluginConfigFields);
            return;
        }

        boolean registered = false;
        String local = blankToNull(compatiblePluginConfigFields.getLocalPath());
        if (local != null && localSourceExists(local)) {
            new ThirdPartyResourcePack(
                    compatiblePluginConfigFields.getPluginName(),
                    local,
                    compatiblePluginConfigFields.getUrl(),
                    compatiblePluginConfigFields.isZips(),
                    compatiblePluginConfigFields.isCluster(),
                    compatiblePluginConfigFields.getReloadCommand());
            registered = true;
        }

        String additional = blankToNull(compatiblePluginConfigFields.getAdditionalLocalPath());
        if (additional != null && localSourceExists(additional)) {
            new ThirdPartyResourcePack(
                    compatiblePluginConfigFields.getPluginName(),
                    additional,
                    null,
                    false,
                    true,
                    compatiblePluginConfigFields.getReloadCommand(),
                    "_shared");
            registered = true;
        }

        if (registered) return;

        String url = blankToNull(compatiblePluginConfigFields.getUrl());
        if (url != null) {
            new ThirdPartyResourcePack(
                    compatiblePluginConfigFields.getPluginName(),
                    null,
                    url,
                    compatiblePluginConfigFields.isZips(),
                    compatiblePluginConfigFields.isCluster(),
                    compatiblePluginConfigFields.getReloadCommand());
            return;
        }

        if (!Bukkit.getPluginManager().isPluginEnabled(compatiblePluginConfigFields.getPluginName())) return;
        Logger.warn("Found " + compatiblePluginConfigFields.getPluginName() + " but none of its configured resource pack paths exist: "
                + describeConfiguredPaths(local, additional) + " . ResourcePackManager will not be able to merge the resource pack from this plugin.");
    }

    /**
     * Returns whether a mixer entry is owned by an integration whose
     * {@code compatible_plugins/*.yml} file has {@code isEnabled: false}.
     *
     * <p>The mixer also accepts arbitrary user-provided ZIPs. Without this
     * explicit ownership check, a generated integration ZIP left behind by an
     * older enabled run is indistinguishable from a manual ZIP and is merged
     * again even though the integration is disabled.</p>
     */
    public static boolean isConfigurationExcludedMixerEntry(String fileName) {
        return fileName != null
                && configurationExcludedMixerEntries.contains(fileName.toLowerCase(Locale.ROOT));
    }

    public static boolean isConfigurationExcludedPlugin(String pluginName) {
        return pluginName != null
                && configurationExcludedPluginNames.contains(pluginName.trim().toLowerCase(Locale.ROOT));
    }

    private static void excludeCompatiblePlugin(CompatiblePluginConfigFields fields) {
        String pluginName = blankToNull(fields.getPluginName());
        if (pluginName == null) {
            Logger.warn("Ignoring disabled compatible-plugin entry " + fields.getFilename()
                    + " because pluginName is blank.");
            return;
        }

        configurationExcludedPluginNames.add(pluginName.toLowerCase(Locale.ROOT));
        thirdPartyResourcePacks.stream()
                .filter(pack -> pluginName.equalsIgnoreCase(pack.pluginName))
                .forEach(pack -> pack.isEnabled = false);
        thirdPartyResourcePacks.removeIf(pack -> pluginName.equalsIgnoreCase(pack.pluginName));

        for (String entryName : ownedMixerEntryNames(pluginName)) {
            configurationExcludedMixerEntries.add(entryName.toLowerCase(Locale.ROOT));
            deleteOwnedMixerEntry(entryName, pluginName);
        }

        RSPLogger.detail("Resource pack integration " + pluginName
                + " is disabled in " + fields.getFilename() + "; excluding it from the merge.");
    }

    private static List<String> ownedMixerEntryNames(String pluginName) {
        String primaryZip = mixerFilename(pluginName, "");
        String sharedZip = mixerFilename(pluginName, "_shared");
        return List.of(
                primaryZip,
                sharedZip,
                primaryZip.replace("_resource_pack.zip", "_cluster_temp"),
                sharedZip.replace("_resource_pack.zip", "_cluster_temp"));
    }

    private static String mixerFilename(String pluginName, String suffix) {
        return pluginName + (suffix == null ? "" : suffix) + "_resource_pack.zip";
    }

    private static void deleteOwnedMixerEntry(String entryName, String pluginName) {
        if (ResourcePackManager.plugin == null) return;
        Path mixerRoot = ResourcePackManager.plugin.getDataFolder().toPath()
                .resolve("mixer").toAbsolutePath().normalize();
        Path entry = mixerRoot.resolve(entryName).normalize();
        if (!mixerRoot.equals(entry.getParent())) {
            Logger.warn("Refusing to clean an invalid mixer entry for disabled integration " + pluginName + ".");
            return;
        }
        if (!Files.exists(entry)) return;

        try {
            if (Files.isDirectory(entry)) {
                Mix.recursivelyDeleteDirectory(entry.toFile());
            } else {
                Files.delete(entry);
            }
            if (Files.exists(entry)) {
                Logger.warn("Could not remove stale mixer entry for disabled integration "
                        + pluginName + ": " + entry);
            } else {
                RSPLogger.detail("Removed stale mixer entry for disabled integration "
                        + pluginName + ": " + entry.getFileName());
            }
        } catch (Exception exception) {
            Logger.warn("Could not remove stale mixer entry for disabled integration "
                    + pluginName + ": " + entry);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static boolean localSourceExists(String localPath) {
        return getPluginRelativeFile(localPath).exists();
    }

    private static File getPluginRelativeFile(String localPath) {
        // Integration paths are plugins-relative on every host, including configs copied from Windows.
        return new File(ResourcePackManager.plugin.getDataFolder().getParentFile(), localPath.replace('\\', '/'));
    }

    private static String describeConfiguredPaths(String local, String additional) {
        if (local != null && additional != null) return getPluginRelativeFile(local).getPath() + ", " + getPluginRelativeFile(additional).getPath();
        if (local != null) return getPluginRelativeFile(local).getPath();
        if (additional != null) return getPluginRelativeFile(additional).getPath();
        return "none";
    }

    private boolean zipThirdPartyPack() {
        if (ZipFile.zip(file, getTarget().toString())) {
            mixerResourcePack = getTarget().toFile();
            return true;
        }
        Logger.warn("Failed to zip resource pack for " + pluginName + " from " + file.getPath());
        mixerResourcePack = null;
        return false;
    }

    private boolean processCluster() {
        if (!file.isDirectory()) {
            Logger.warn("Cluster path for " + pluginName + " is not a directory: " + file.getPath());
            mixerResourcePack = null;
            return false;
        }

        File[] clusterContents = file.listFiles();
        if (clusterContents == null || clusterContents.length == 0) {
            Logger.warn("Cluster directory for " + pluginName + " is empty: " + file.getPath());
            mixerResourcePack = null;
            return false;
        }

        File mixerDir = new File(ResourcePackManager.plugin.getDataFolder().toString() + File.separatorChar + "mixer");
        if (!mixerDir.exists()) mixerDir.mkdir();

        // Merge all cluster sub-packs into a temporary directory
        // Derive temp dir name from mixerFilename so multiple registrations under the same pluginName don't collide.
        String clusterTempName = mixerFilename.replace("_resource_pack.zip", "_cluster_temp");
        File clusterTemp = new File(mixerDir.getPath() + File.separatorChar + clusterTempName);
        if (clusterTemp.exists()) Mix.recursivelyDeleteDirectory(clusterTemp);
        clusterTemp.mkdir();

        RSPLogger.detail("Processing cluster for " + pluginName + " with " + clusterContents.length + " resource packs");

        for (File resourcePackFolder : clusterContents) {
            if (!resourcePackFolder.isDirectory()) {
                RSPLogger.detail("Skipping non-directory in cluster: " + resourcePackFolder.getName());
                continue;
            }

            File[] resourcePackContents = resourcePackFolder.listFiles();
            if (resourcePackContents == null) continue;

            for (File contentFolder : resourcePackContents) {
                if (!contentFolder.isDirectory()) continue;

                try {
                    Mix.recursivelyCopyDirectory(contentFolder, clusterTemp);
                } catch (Exception e) {
                    Logger.warn("Failed to copy " + contentFolder.getPath() + " to cluster temp");
                    e.printStackTrace();
                }
            }
        }

        // Zip the merged cluster content so it participates in priority ordering like any other pack
        File targetZip = getTarget().toFile();
        if (ZipFile.zip(clusterTemp, targetZip.getAbsolutePath())) {
            mixerResourcePack = targetZip;
            RSPLogger.detail("Created merged cluster pack: " + targetZip.getAbsolutePath());
        } else {
            Logger.warn("Failed to zip merged cluster for " + pluginName);
            mixerResourcePack = null;
        }

        // Clean up temp directory
        Mix.recursivelyDeleteDirectory(clusterTemp);
        RSPLogger.detail("Finished processing cluster for " + pluginName);
        return mixerResourcePack != null;
    }

    private boolean processLocal(String localPath) {
        this.file = getPluginRelativeFile(localPath);

        if (!file.exists()) {
            Logger.warn("Found " + pluginName + " but could not find resource pack at location " + file.getPath() + " ! ResourcePackManager will not be able to merge the resource pack from this plugin.");
            isEnabled = false;
            return false;
        }

        return true;
    }

    public boolean process() {
        if (!isEnabled) return false;

        //Check if a copy already exists in the mixer folder and if it is up-to-date
        if (localPath != null && zips && mixerCloneExists()) {
            // getSHA1 returns null when hashing fails — treat that as "not
            // up-to-date" (re-clone) instead of NPEing on the compare.
            String existingCloneSHA1 = getSHA1(getTarget().toFile());
            if (existingCloneSHA1 != null && existingCloneSHA1.equals(SHA1)) {
                mixerResourcePack = getTarget().toFile();
                return true;
            } else {
                RSPLogger.detail("Clearing outdated resource pack in " + getTarget());
                getTarget().toFile().delete();
            }
        }

        return cloneResourcePackFile();
    }

    private String getSHA1(File file) {
        try {
            return Sha1.hex(file);
        } catch (Exception e) {
            Logger.warn("Failed to generate SHA1 for " + file.getAbsolutePath());
            return null;
        }
    }

    private boolean cloneResourcePackFile() {
        if (localPath != null) return cloneLocalRSP();
        return cloneRemoteRSP();
    }

    private boolean cloneLocalRSP() {
        if (!zips) return zipThirdPartyPack();
        File mixerFolder = new File(ResourcePackManager.plugin.getDataFolder().toString(), "mixer");
        if (!mixerFolder.exists()) mixerFolder.mkdirs();
        return attemptClone();
    }

    private boolean attemptClone() {
        try {
            RSPLogger.detail("Cloning resource pack from " + file.toPath());
            mixerResourcePack = Files.copy(Path.of(file.getAbsolutePath()), Path.of(getTarget().toAbsolutePath().toString()), StandardCopyOption.REPLACE_EXISTING).toFile();
            return true;
        } catch (java.nio.file.FileSystemException e) {
            Logger.warn("Resource pack file is locked for " + pluginName + "; staging will retry after the source settles.");
            mixerResourcePack = null;
            return false;
        } catch (Exception e) {
            Logger.warn("Failed to clone resource pack from " + file.getPath() + " to the mixer folder!");
            e.printStackTrace();
            mixerResourcePack = null;
            return false;
        }
    }

    private boolean cloneRemoteRSP() {
        RSPLogger.detail("Getting resource pack from remote URL! This is not ideal but not optional for some plugins. URL: " + url);
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet httpGet = new HttpGet(url);
            try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
                int statusCode = response.getCode();
                RSPLogger.detail("Response status code: " + statusCode);

                if (statusCode == 200) {
                    HttpEntity responseEntity = response.getEntity();
                    if (responseEntity != null) {
                        // Save the response as a zip file
                        File zipFile = getTarget().toFile();
                        if (zipFile.exists()) {
                            RSPLogger.detail("Target file exists, deleting it: " + zipFile.getAbsolutePath());
                            zipFile.delete();
                        }
                        zipFile.createNewFile();

                        try (InputStream inStream = new BufferedInputStream(responseEntity.getContent());
                             FileOutputStream outStream = new FileOutputStream(zipFile)) {

                            byte[] buffer = new byte[4096];
                            int bytesRead;
                            while ((bytesRead = inStream.read(buffer)) != -1) {
                                outStream.write(buffer, 0, bytesRead);
                            }

                            RSPLogger.detail("Successfully downloaded the resource pack to " + zipFile.getAbsolutePath());
                            mixerResourcePack = zipFile;
                            return true;
                        } catch (Exception e) {
                            Logger.warn("Failed to write resource pack from remote!");
                        }
                    } else {
                        Logger.warn("Response entity is null");
                    }
                } else {
                    Logger.warn("Unexpected response status: " + statusCode);
                }
            } catch (Exception e) {
                Logger.warn("Failed to communicate with remote server when downloading resource pack for plugin " + pluginName + "!");
            }
        } catch (Exception e) {
            Logger.warn("Failed to connect to url " + url + " to download resource pack for plugin " + pluginName);
        }
        mixerResourcePack = null;
        return false;
    }

    private boolean mixerCloneExists() {
        return Files.exists(getTarget());
    }

    private Path getTarget() {
        return Path.of(ResourcePackManager.plugin.getDataFolder().toString(), "mixer", mixerFilename);
    }

    private boolean stageLatestSource() {
        if (!isEnabled) return true;
        if (localPath != null && !file.exists()) {
            Logger.warn("Resource pack source for " + pluginName + " is missing: " + file.getPath());
            return false;
        }
        if (cluster) return processCluster();
        SHA1 = getSourceFingerprint();
        return process();
    }

    private String getSourceFingerprint() {
        if (localPath == null && url != null) {
            return "REMOTE:" + url;
        }
        if (file == null || !file.exists()) return null;
        if (!file.isDirectory()) return getSHA1(file);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            Path root = file.toPath();
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile)
                        .sorted()
                        .forEach(path -> updateDirectoryFingerprint(digest, root, path));
            }
            return Sha1.bytesToHexString(digest.digest());
        } catch (Exception e) {
            Logger.warn("Failed to fingerprint resource pack directory for " + pluginName + ": " + file.getPath());
            return null;
        }
    }

    private static void updateDirectoryFingerprint(MessageDigest digest, Path root, Path path) {
        try {
            String relativePath = root.relativize(path).toString().replace(File.separatorChar, '/');
            digest.update(relativePath.getBytes(StandardCharsets.UTF_8));
            digest.update(Long.toString(Files.size(path)).getBytes(StandardCharsets.UTF_8));
            digest.update(Long.toString(Files.getLastModifiedTime(path).toMillis()).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // The caller will see a changed fingerprint on the next stability check.
        }
    }
}
