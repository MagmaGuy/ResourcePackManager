package com.magmaguy.resourcepackmanager.thirdparty;

import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.ZipFile;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.config.DefaultConfig;
import com.magmaguy.resourcepackmanager.config.compatibleplugins.CompatiblePluginConfigFields;
import com.magmaguy.resourcepackmanager.utils.SHA1Generator;
import lombok.Getter;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpEntity;
import org.bukkit.Bukkit;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;

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

    public ThirdPartyResourcePack(String pluginName, String localPath, String url, boolean encrypts, boolean distributes, boolean zips, String reloadCommand) {
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

        if (!zips) zipThirdPartyPack();

        if (localPath != null) SHA1 = getSHA1(file);

        process();

        if (DefaultConfig.getPriorityOrder().contains(pluginName))
            priority = DefaultConfig.getPriorityOrder().indexOf(pluginName);

        thirdPartyResourcePacks.add(this);
        done = true;
    }

    public static void shutdown() {
        thirdPartyResourcePacks.clear();
    }

    public static void initializeThirdPartyResourcePack(CompatiblePluginConfigFields compatiblePluginConfigFields) {
        new ThirdPartyResourcePack(
                compatiblePluginConfigFields.getPluginName(),
                compatiblePluginConfigFields.getLocalPath(),
                compatiblePluginConfigFields.getUrl(),
                compatiblePluginConfigFields.isEncrypts(),
                compatiblePluginConfigFields.isDistributes(),
                compatiblePluginConfigFields.isZips(),
                compatiblePluginConfigFields.getReloadCommand());
    }

    private void zipThirdPartyPack() {
        ZipFile.zip(file, getTarget().toString());
        file = new File(getTarget().toUri());
        mixerResourcePack = file;
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
