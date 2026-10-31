package com.magmaguy.resourcepackmanager.craftengine;

import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.config.DefaultConfig;
import com.magmaguy.resourcepackmanager.thirdparty.ThirdPartyResourcePack;
import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;

/**
 * Detects CraftEngine sending its own resource pack next to RSPM's merged one.
 *
 * <p>With its default config CraftEngine hosts its pack and sends it to every joining player
 * ({@code resource-pack.delivery.send-on-join: true}). RSPM merges that same pack into the one
 * it sends, so players then download two packs, CraftEngine's twice over. RSPM only reports
 * this; it never edits CraftEngine's config.</p>
 */
public final class CraftEngineDelivery {
    static final String PLUGIN_NAME = "CraftEngine";
    static final String SEND_ON_JOIN = "resource-pack.delivery.send-on-join";

    private CraftEngineDelivery() {
    }

    /**
     * @return whether CraftEngine sends its pack to joining players, per its config.yml. A
     *         missing file counts as CraftEngine's default (true); an unreadable one as false,
     *         so RSPM never claims a setting it could not read.
     */
    static boolean sendsPackOnJoin(File craftEngineConfig) {
        if (!craftEngineConfig.isFile()) return true;
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(craftEngineConfig);
        } catch (IOException | InvalidConfigurationException unreadable) {
            return false;
        }
        return config.getBoolean(SEND_ON_JOIN, true);
    }

    /**
     * Players get the pack twice only when RSPM sends its merged pack, that merge includes
     * CraftEngine's pack, and CraftEngine sends its own as well.
     */
    static boolean doubleDelivery(boolean rspmSendsPack, boolean craftEngineMerged, File craftEngineConfig) {
        return rspmSendsPack && craftEngineMerged && sendsPackOnJoin(craftEngineConfig);
    }

    /** Reads CraftEngine's config.yml now, so a changed setting shows without a restart. */
    public static boolean doubleDelivery() {
        Plugin craftEngine = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        if (craftEngine == null || !craftEngine.isEnabled()) return false;
        return doubleDelivery(DefaultConfig.isAutoHost() || DefaultConfig.isSelfHostForce(),
                !ThirdPartyResourcePack.isConfigurationExcludedPlugin(PLUGIN_NAME),
                new File(craftEngine.getDataFolder(), "config.yml"));
    }

    public static void warnIfDoubleDelivery() {
        if (!doubleDelivery()) return;
        Logger.warn("CraftEngine also sends its own resource pack to joining players ("
                + SEND_ON_JOIN + " is true in plugins/CraftEngine/config.yml). ResourcePackManager already"
                + " merges CraftEngine's pack into the pack it sends, so players download it twice. Set"
                + " send-on-join to false in CraftEngine's config.yml, then run /ce reload all.");
    }
}
