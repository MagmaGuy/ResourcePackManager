package com.magmaguy.resourcepackmanager.thirdparty;

import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.ZipFile;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.config.DefaultConfig;
import com.magmaguy.resourcepackmanager.config.compatibleplugins.CompatiblePluginConfigFields;
import com.magmaguy.resourcepackmanager.mixer.Mix;
import com.magmaguy.resourcepackmanager.utils.SHA1Generator;
import lombok.Getter;
import lombok.Setter;
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
import java.util.HashSet;
import java.util.Objects;

public class ThirdPartyResourcePack implements GeneratorInterface {
    public static HashSet<ThirdPartyResourcePack> thirdPartyResourcePacks = new HashSet<>();

    @Getter
    private final String pluginName;
    @Getter
    private final String mixerFilename;
    private final String localPath;
    private final String url;
    @Getter
    private File file = null;
    private boolean encrypts;
    private boolean distributes;
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

    private int ticksWithoutChange = 0;
    private boolean consideredStable = false;
    @Setter
    private boolean stableResourcePackSent = false;

    public ThirdPartyResourcePack(String pluginName, String localPath, String url, boolean encrypts, boolean distributes, boolean zips, boolean cluster, String reloadCommand) {
        this.pluginName = pluginName;
        this.mixerFilename = pluginName + "_resource_pack.zip";
        this.url = url;
        this.localPath = localPath;

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

        this.encrypts = encrypts;
        this.distributes = distributes;
        this.reloadCommand = reloadCommand;
        this.zips = zips;
        this.cluster = cluster;

        // If this is a cluster, process each resource pack in the folder
        if (cluster) {
            processCluster();
        } else if (!zips) {
            zipThirdPartyPack();
        }

        if (localPath != null && !cluster) SHA1 = getSHA1(file);

        // Check if source file matches existing mixer file - if so, no changes occurred and it's stable
        if (!cluster && localPath != null) {
            File existingMixerFile = getTarget().toFile();
            if (existingMixerFile.exists()) {
                String existingMixerSHA1 = getSHA1(existingMixerFile);
                if (Objects.equals(SHA1, existingMixerSHA1)) {
                    consideredStable = true;
                }
            }
        }

        if (!cluster) process();

        if (DefaultConfig.getPriorityOrder().contains(pluginName))
            priority = DefaultConfig.getPriorityOrder().indexOf(pluginName);

        thirdPartyResourcePacks.add(this);
        done = true;
    }

    private static BukkitTask resourcePackChangeWatcher = null;

    public static void startResourcePackChangeWatchdog() {
        resourcePackChangeWatcher = new BukkitRunnable() {
            @Override
            public void run() {
                boolean readyToSend = true;
                boolean stableAlreadySent = true;
                for (ThirdPartyResourcePack thirdPartyResourcePack : thirdPartyResourcePacks) {
                    if (!thirdPartyResourcePack.isEnabled || thirdPartyResourcePack.file == null) continue;
                    if (!Objects.equals(thirdPartyResourcePack.getSHA1(thirdPartyResourcePack.file), thirdPartyResourcePack.SHA1)) {
                        thirdPartyResourcePack.ticksWithoutChange = 0;
                        thirdPartyResourcePack.SHA1 = thirdPartyResourcePack.getSHA1(thirdPartyResourcePack.file);
                        if (thirdPartyResourcePack.consideredStable) {
                            thirdPartyResourcePack.consideredStable = false;
                            thirdPartyResourcePack.stableResourcePackSent = false;
                            Logger.info("Resource pack for " + thirdPartyResourcePack.pluginName + " has changed, considering it unstable.");
                        }
                    }
                    if (!thirdPartyResourcePack.stableResourcePackSent) stableAlreadySent = false;
                    if (!thirdPartyResourcePack.consideredStable) readyToSend = false;
                    if (thirdPartyResourcePack.consideredStable) continue;
                    thirdPartyResourcePack.ticksWithoutChange++;
                    if (thirdPartyResourcePack.ticksWithoutChange == 10) {
                        //If nothing has changed for 10 seconds, then it should be considered to be stable
                        thirdPartyResourcePack.consideredStable = true;
                        Logger.info("Resource pack for " + thirdPartyResourcePack.pluginName + " has not changed for 10 seconds, considering it stable.");
                    }
                }

                if (!stableAlreadySent && readyToSend) {
                    Logger.info("All resource packs are stable, sending resource pack to players.");
                    tagAsResourcePackSent();
                    Mix.mixResourcePacks();
                }
            }
        }.runTaskTimerAsynchronously(ResourcePackManager.plugin, 20, 20);
    }

    public static void tagAsResourcePackSent(){
        for (ThirdPartyResourcePack thirdPartyResourcePack : thirdPartyResourcePacks) {
            thirdPartyResourcePack.stableResourcePackSent = true;
        }
    }

    public static void shutdown() {
        thirdPartyResourcePacks.clear();
        resourcePackChangeWatcher.cancel();
    }

    public static void initializeThirdPartyResourcePack(CompatiblePluginConfigFields compatiblePluginConfigFields) {
        new ThirdPartyResourcePack(
                compatiblePluginConfigFields.getPluginName(),
                compatiblePluginConfigFields.getLocalPath(),
                compatiblePluginConfigFields.getUrl(),
                compatiblePluginConfigFields.isEncrypts(),
                compatiblePluginConfigFields.isDistributes(),
                compatiblePluginConfigFields.isZips(),
                compatiblePluginConfigFields.isCluster(),
                compatiblePluginConfigFields.getReloadCommand());
    }

    private void zipThirdPartyPack() {
        ZipFile.zip(file, getTarget().toString());
        file = new File(getTarget().toUri());
        mixerResourcePack = file;
    }

    private void processCluster() {
        if (!file.isDirectory()) {
            Logger.warn("Cluster path for " + pluginName + " is not a directory: " + file.getPath());
            isEnabled = false;
            return;
        }

        File[] clusterContents = file.listFiles();
        if (clusterContents == null || clusterContents.length == 0) {
            Logger.warn("Cluster directory for " + pluginName + " is empty: " + file.getPath());
            isEnabled = false;
            return;
        }

        File mixerDir = new File(ResourcePackManager.plugin.getDataFolder().toString() + File.separatorChar + "mixer");
        if (!mixerDir.exists()) mixerDir.mkdir();

        Logger.info("Processing cluster for " + pluginName + " with " + clusterContents.length + " resource packs");

        for (File resourcePackFolder : clusterContents) {
            if (!resourcePackFolder.isDirectory()) {
                Logger.info("Skipping non-directory in cluster: " + resourcePackFolder.getName());
                continue;
            }

            // Each folder in the cluster is a resource pack - copy its contents to mixer
            File[] resourcePackContents = resourcePackFolder.listFiles();
            if (resourcePackContents == null) continue;

            for (File contentFolder : resourcePackContents) {
                if (!contentFolder.isDirectory()) continue;

                File targetFolder = new File(mixerDir.getPath() + File.separatorChar + contentFolder.getName());
                try {
                    recursivelyCloneDirectory(contentFolder, targetFolder);
                    Logger.info("Copied " + contentFolder.getName() + " from " + resourcePackFolder.getName() + " to mixer folder");
                } catch (Exception e) {
                    Logger.warn("Failed to copy " + contentFolder.getPath() + " to mixer folder");
                    e.printStackTrace();
                }
            }
        }

        // For clusters, we don't track a single mixerResourcePack since they get copied directly
        // Set isEnabled to false so it doesn't try to process further as a single pack
        isEnabled = false;
        Logger.info("Finished processing cluster for " + pluginName);
    }

    private void recursivelyCloneDirectory(File source, File target) {
        if (source.isDirectory()) {
            if (!target.exists()) target.mkdir();
            File[] files = source.listFiles();
            if (files != null) {
                for (File child : files) {
                    recursivelyCloneDirectory(child, new File(target.getPath() + File.separatorChar + child.getName()));
                }
            }
        } else {
            try {
                Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                Logger.warn("Failed to copy file " + source.getPath() + " to " + target.getPath());
            }
        }
    }

    private boolean processLocal(String localPath) {
        this.file = new File(ResourcePackManager.plugin.getDataFolder().getParentFile().toPath().toString() + File.separatorChar + localPath);

        if (!file.exists()) {
            Logger.warn("Found " + pluginName + " but could not find resource pack at location " + file.getPath() + " ! ResourcePackManager will not be able to merge the resource pack from this plugin.");
            isEnabled = false;
            return false;
        }

        return true;
    }

    public void process() {
        if (!isEnabled) return;

        //Check if a copy already exists in the mixer folder and if it is up-to-date
        if (localPath != null && mixerCloneExists()) {
            if (getSHA1(new File(getTarget().toUri())).equals(SHA1)) {
                mixerResourcePack = getTarget().toFile();
                return;
            } else {
                Logger.info("Clearing outdated resource pack in " + getTarget());
                getTarget().toFile().delete();
            }
        }

        if (encrypts) decrypt();
        if (distributes) unpublish();

        cloneResourcePackFile();
    }

    private String getSHA1(File file) {
        try {
            return SHA1Generator.sha1CodeString(file);
        } catch (Exception e) {
            Logger.warn("Failed to generate SHA1 for " + file.getAbsolutePath());
            return null;
        }
    }

    @Override
    public void decrypt() {
        //Implementation depends on extended classes
    }

    @Override
    public void unpublish() {
        //Implementation depends on extended classes
    }

    @Override
    public void cloneResourcePackFile() {
        if (localPath != null) cloneLocalRSP();
        else cloneRemoteRSP();
    }

    private void cloneLocalRSP() {
        try {
            if (zips) {
                Logger.info("Cloning resource pack from " + file.toPath());
                mixerResourcePack = Files.copy(Path.of(file.getAbsolutePath()), Path.of(getTarget().toAbsolutePath().toString()), StandardCopyOption.REPLACE_EXISTING).toFile();
            }
        } catch (Exception e) {
            Logger.warn("Failed to clone resource pack from " + file.getPath() + " to the mixer folder!");
            e.printStackTrace();
        }
    }

    public void cloneRemoteRSP() {
        Logger.info("Getting resource pack from remote URL! This is not ideal but not optional for some plugins. URL: " + url);
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet httpGet = new HttpGet(url);
            try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
                int statusCode = response.getCode();
                Logger.info("Response status code: " + statusCode);

                if (statusCode == 200) {
                    HttpEntity responseEntity = response.getEntity();
                    if (responseEntity != null) {
                        // Save the response as a zip file
                        File zipFile = getTarget().toFile();
                        if (zipFile.exists()) {
                            Logger.info("Target file exists, deleting it: " + zipFile.getAbsolutePath());
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

                            Logger.info("Successfully downloaded the resource pack to " + zipFile.getAbsolutePath());
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
    }

    @Override
    public void reload() {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), reloadCommand);
    }

    private boolean mixerCloneExists() {
        return Files.exists(getTarget());
    }

    private Path getTarget() {
        return Path.of(ResourcePackManager.plugin.getDataFolder().toString(), "mixer", mixerFilename);
    }
}
