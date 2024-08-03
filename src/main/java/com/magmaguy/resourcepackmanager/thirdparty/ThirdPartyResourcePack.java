package com.magmaguy.resourcepackmanager.thirdparty;

import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.ZipFile;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.config.DefaultConfig;
import com.magmaguy.resourcepackmanager.utils.SHA1Generator;
import lombok.Getter;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpEntity;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class ThirdPartyResourcePack implements GeneratorInterface {
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
        mixerFilename = pluginName + "_" + (file.getName().endsWith(".zip") ? file.getName() : file.getName() + ".zip");
        if (!zips) {
            ZipFile.zip(file, getTarget().toString());
            file = new File(getTarget().toUri());
            resourcePackUpdated = true;
            mixerFilename = file.getName();
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
//        } else if (local && mixerCloneExists()) getTarget().toFile().delete();
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
            } else {
                if (mixerCloneExists()) getTarget().toFile().delete();
                Logger.info("Cloning resource pack from " + file.toPath());
                ZipFile.zip(file, getTarget().toString());
            }
        } catch (Exception e) {
            Logger.warn("Failed to clone resource pack from " + file.getPath() + " to the mixer folder!");
            e.printStackTrace();
        }
    }

    private void cloneRemoteRSP() {
        Logger.info("Getting resource pack from remote URL! This is not ideal but not optional for some plugins. URL: " + url);
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost httpPost = new HttpPost(url);
            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                HttpEntity responseEntity = response.getEntity();

                if (responseEntity != null) {
                    // Save the response as a zip file
                    File zipFile = getTarget().toFile();
                    if (zipFile.exists()) zipFile.delete();
                    zipFile.createNewFile();
                    try (FileOutputStream outStream = new FileOutputStream(zipFile)) {
                        responseEntity.writeTo(outStream);
                    } catch (Exception e) {
                        Logger.warn("Failed to write resource pack from remote!");
                    }
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
