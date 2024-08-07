package com.magmaguy.resourcepackmanager.thirdparty;

import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.ZipFile;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.config.DefaultConfig;
import com.magmaguy.resourcepackmanager.utils.SHA1Generator;
import lombok.Getter;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
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

public class ThirdPartyResourcePack implements GeneratorInterface {
    @Getter
    private final String pluginName;
    @Getter
    private File file = null;
    private String url;
    private boolean encrypts;
    private boolean distributes;
    private boolean zips;
    private boolean local;
    private String reloadCommand;
    @Getter
    private boolean isEnabled;
    private String SHA1;
    private boolean resourcePackUpdated = false;
    @Getter
    private File mixerResourcePack = null;
    @Getter
    private String mixerFilename;
    @Getter
    private int priority = -1;

    public ThirdPartyResourcePack(String pluginName, String path, boolean encrypts, boolean distributes, boolean zips, boolean local, String reloadCommand) {
        this.pluginName = pluginName;
        this.mixerFilename = pluginName + "_resource_pack.zip";
        isEnabled = Bukkit.getPluginManager().isPluginEnabled(pluginName);
        if (isEnabled)
            Logger.info("Initializing " + pluginName + "'s resource pack");
        else return;
        this.local = local;
        if (local)
            this.file = new File(ResourcePackManager.plugin.getDataFolder().getParentFile().toPath().toString() + File.separatorChar + path);
        else
            this.url = path;
        if (file != null && !file.exists()) {
            Logger.warn("Found " + pluginName + " but could not find resource pack at location " + file.getPath() + " ! ResourcePackManager will not be able to merge the resource pack from this plugin.");
            isEnabled = false;
            return;
        }
        this.encrypts = encrypts;
        this.distributes = distributes;
        this.reloadCommand = reloadCommand;
        this.zips = zips;
        if (!zips) {
            ZipFile.zip(file, getTarget().toString());
            file = new File(getTarget().toUri());
            resourcePackUpdated = true;
            mixerResourcePack = file;
        }
        if (isEnabled && local) SHA1 = getSHA1(file);
        process();
        if (DefaultConfig.getPriorityOrder().contains(pluginName))
            priority = DefaultConfig.getPriorityOrder().indexOf(pluginName);
    }

    public void process() {
        if (!isEnabled) return;
        if (local && zips) {
            if (mixerCloneExists()) {
                if (getSHA1(new File(getTarget().toUri())).equals(SHA1)) {
                    mixerResourcePack = getTarget().toFile();
                    return;
                } else {
                    Logger.info("Clearing outdated resource pack in " + getTarget());
                    getTarget().toFile().delete();
                }
            }
        }
        if (encrypts) decrypt();
        if (distributes) unpublish();
        if (zips)
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
        if (local) cloneLocalRSP();
        else cloneRemoteRSP();
        resourcePackUpdated = true;
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
