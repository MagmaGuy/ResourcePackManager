package com.magmaguy.resourcepackmanager.thirdparty;

import com.magmaguy.resourcepackmanager.Logger;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.config.DefaultConfig;
import com.magmaguy.resourcepackmanager.utils.SHA1Generator;
import lombok.Getter;
import org.bukkit.Bukkit;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class ThirdPartyResourcePack implements GeneratorInterface {
    @Getter
    private File file;
    private boolean encrypts;
    private boolean distributes;
    private String reloadCommand;
    @Getter
    private boolean isEnabled;
    private String SHA1;
    private boolean resourcePackUpdated = false;
    @Getter
    private File mixerResourcePack = null;
    @Getter
    private int priority = -1;

    public ThirdPartyResourcePack(String pluginName, String path, boolean encrypts, boolean distributes, String reloadCommand) {
        isEnabled = Bukkit.getPluginManager().isPluginEnabled(pluginName);
        if (isEnabled)
            Logger.info("Initializing " + pluginName + "'s resource pack");
        else return;
        this.file = new File(ResourcePackManager.plugin.getDataFolder().getParentFile().toPath().toString() + File.separatorChar + path);
        if (!file.exists()) {
            Logger.warn("Found " + pluginName + " but could not find resource pack at location " + file.getPath() + " ! ResourcePackManager will not be able to merge the resource pack from this plugin.");
            isEnabled = false;
        }
        this.encrypts = encrypts;
        this.distributes = distributes;
        this.reloadCommand = reloadCommand;
        if (isEnabled) SHA1 = getSHA1(file);
        process();
        if (DefaultConfig.getPriorityOrder().contains(pluginName))
            priority = DefaultConfig.getPriorityOrder().indexOf(pluginName);
    }

    public void process() {
        if (!isEnabled) return;
        if (mixerCloneExists()) {
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
        try {
            Logger.info("Cloning resource pack from " + file.toPath());
            mixerResourcePack = Files.copy(Path.of(file.getAbsolutePath()), Path.of(getTarget().toAbsolutePath().toString()), StandardCopyOption.REPLACE_EXISTING).toFile();
        } catch (Exception e) {
            Logger.warn("Failed to clone resource pack from " + file.getPath() + " to the mixer folder!");
            e.printStackTrace();
        }

        resourcePackUpdated = true;
    }

    @Override
    public void reload() {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), reloadCommand);
    }

    private boolean mixerCloneExists() {
        return Files.exists(getTarget());
    }

    private Path getTarget() {
        return Path.of(ResourcePackManager.plugin.getDataFolder().toString(), "mixer", file.getName());
    }
}
