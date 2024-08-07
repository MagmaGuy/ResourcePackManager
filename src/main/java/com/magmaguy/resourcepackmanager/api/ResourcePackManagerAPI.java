package com.magmaguy.resourcepackmanager.api;

import com.magmaguy.magmacore.command.CommandManager;
import com.magmaguy.resourcepackmanager.commands.ReloadCommand;
import com.magmaguy.resourcepackmanager.thirdparty.ThirdPartyResourcePack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.util.HashMap;

public class ResourcePackManagerAPI {
    public static HashMap<String, ThirdPartyResourcePack> thirdPartyResourcePackHashMap = new HashMap<>();

    /**
     * Please read and follow the parameters carefully.
     * @param pluginName The name of the plugin as it appears in the plugin list. Case-sensitive.
     * @param path The absolute path to the resource pack file (zipped or folder), or the URL to the resource pack. Zipped, local resource packs are strongly recommended.
     * @param encrypts Whether the pack can be encrypted by the plugin. Currently does nothing.
     * @param distributes Whether the plugin can distribute the pack. Currently does nothing.
     * @param zips Whether the resource pack linked to is zipped.
     * @param local Whether the resource pack is local or not, meaning whether it appears in the directory of the server or is provided as a download link. Local is strongly recommended.
     * @param reloadCommand The reload command of the plugin adding a pack. Currently does nothing.
     */
    public static void registerResourcePack(String pluginName,
                                            String path,
                                            boolean encrypts,
                                            boolean distributes,
                                            boolean zips,
                                            boolean local,
                                            String reloadCommand) {
        thirdPartyResourcePackHashMap.put(pluginName,
                new ThirdPartyResourcePack(pluginName, path, encrypts, distributes, zips, local, reloadCommand));
    }

    /**
     * Reloads the plugin, thereby redoing everything necessary to merge and host the resource pack
     */
    public static void reloadResourcePack(){
        ReloadCommand.reloadPlugin(Bukkit.getConsoleSender());
    }
}
