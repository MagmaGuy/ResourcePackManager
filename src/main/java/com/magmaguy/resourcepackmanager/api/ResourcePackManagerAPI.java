package com.magmaguy.resourcepackmanager.api;

import com.magmaguy.resourcepackmanager.commands.ReloadCommand;
import com.magmaguy.resourcepackmanager.thirdparty.ThirdPartyResourcePack;
import org.bukkit.Bukkit;

import java.util.HashMap;

public class ResourcePackManagerAPI {
    public static HashMap<String, ThirdPartyResourcePack> thirdPartyResourcePackHashMap = new HashMap<>();

    /**
     * Registers a resource pack with the ResourcePackManager.
     * Either localPath or url must be provided (non-null), but not both.
     *
     * @param pluginName    The name of the plugin as it appears in the plugin list. Case-sensitive.
     * @param localPath     The relative path to the resource pack file (zipped or folder) from the plugins directory, or null if using URL.
     * @param url           The URL to download the resource pack from, or null if using local path.
     * @param encrypts      Whether the pack can be encrypted by the plugin. Currently does nothing.
     * @param distributes   Whether the plugin can distribute the pack. Currently does nothing.
     * @param zips          Whether the resource pack is already zipped. If false, ResourcePackManager will zip it.
     * @param reloadCommand The reload command of the plugin adding a pack. Currently does nothing.
     */
    public static void registerResourcePack(String pluginName,
                                            String localPath,
                                            String url,
                                            boolean encrypts,
                                            boolean distributes,
                                            boolean zips,
                                            String reloadCommand) {
        thirdPartyResourcePackHashMap.put(pluginName,
                new ThirdPartyResourcePack(pluginName, localPath, url, encrypts, distributes, zips, reloadCommand));
    }

    /**
     * Registers a local resource pack with the ResourcePackManager.
     *
     * @param pluginName    The name of the plugin as it appears in the plugin list. Case-sensitive.
     * @param localPath     The relative path to the resource pack file (zipped or folder) from the plugins directory.
     * @param encrypts      Whether the pack can be encrypted by the plugin. Currently does nothing.
     * @param distributes   Whether the plugin can distribute the pack. Currently does nothing.
     * @param zips          Whether the resource pack is already zipped. If false, ResourcePackManager will zip it.
     * @param reloadCommand The reload command of the plugin adding a pack. Currently does nothing.
     */
    public static void registerLocalResourcePack(String pluginName,
                                                 String localPath,
                                                 boolean encrypts,
                                                 boolean distributes,
                                                 boolean zips,
                                                 String reloadCommand) {
        thirdPartyResourcePackHashMap.put(pluginName,
                new ThirdPartyResourcePack(pluginName, localPath, null, encrypts, distributes, zips, reloadCommand));
    }

    /**
     * Registers a remote resource pack with the ResourcePackManager.
     * The resource pack will be downloaded from the provided URL.
     *
     * @param pluginName    The name of the plugin as it appears in the plugin list. Case-sensitive.
     * @param url           The URL to download the resource pack from.
     * @param encrypts      Whether the pack can be encrypted by the plugin. Currently does nothing.
     * @param distributes   Whether the plugin can distribute the pack. Currently does nothing.
     * @param zips          Whether the resource pack from the URL is already zipped. If false, ResourcePackManager will zip it.
     * @param reloadCommand The reload command of the plugin adding a pack. Currently does nothing.
     */
    public static void registerRemoteResourcePack(String pluginName,
                                                  String url,
                                                  boolean encrypts,
                                                  boolean distributes,
                                                  boolean zips,
                                                  String reloadCommand) {
        thirdPartyResourcePackHashMap.put(pluginName,
                new ThirdPartyResourcePack(pluginName, null, url, encrypts, distributes, zips, reloadCommand));
    }

    /**
     * Reloads the plugin, thereby redoing everything necessary to merge and host the resource pack
     */
    public static void reloadResourcePack() {
        ReloadCommand.reloadPlugin(Bukkit.getConsoleSender());
    }
}