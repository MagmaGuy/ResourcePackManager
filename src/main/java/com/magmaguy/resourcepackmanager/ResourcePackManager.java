package com.magmaguy.resourcepackmanager;

import com.magmaguy.magmacore.MagmaCore;
import com.magmaguy.magmacore.command.CommandManager;
import com.magmaguy.magmacore.initialization.PluginInitializationConfig;
import com.magmaguy.magmacore.initialization.PluginInitializationContext;
import com.magmaguy.magmacore.initialization.PluginInitializationState;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.autohost.AutoHost;
import com.magmaguy.resourcepackmanager.commands.DataComplianceRequestCommand;
import com.magmaguy.resourcepackmanager.commands.ReloadCommand;
import com.magmaguy.resourcepackmanager.config.BlueprintFolder;
import com.magmaguy.resourcepackmanager.itemsadder.ItemsAdderCommand;
import com.magmaguy.resourcepackmanager.itemsadder.ItemsAdderDismissedConfig;
import com.magmaguy.resourcepackmanager.itemsadder.ItemsAdderWarningListener;
import com.magmaguy.resourcepackmanager.config.DataConfig;
import com.magmaguy.resourcepackmanager.config.DefaultConfig;
import com.magmaguy.resourcepackmanager.config.compatibleplugins.CompatiblePluginConfig;
import com.magmaguy.resourcepackmanager.config.compatibleplugins.CompatiblePluginConfigFields;
import com.magmaguy.resourcepackmanager.playermanager.PlayerManager;
import com.magmaguy.resourcepackmanager.thirdparty.ThirdPartyResourcePack;
import com.magmaguy.resourcepackmanager.utils.VersionChecker;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public class ResourcePackManager extends JavaPlugin {

    public static JavaPlugin plugin;

    @Override
    public void onEnable() {
        Bukkit.getLogger().info("\n" +
                "  ___  ___ ___ __  __                             \n" +
                " | _ \\/ __| _ \\  \\/  |__ _ _ _  __ _ __ _ ___ _ _ \n" +
                " |   /\\__ \\  _/ |\\/| / _` | ' \\/ _` / _` / -_) '_|\n" +
                " |_|_\\|___/_| |_|  |_\\__,_|_||_\\__,_\\__, \\___|_|  \n" +
                "                                    |___/         ");
        Bukkit.getLogger().info("ResourcePackManager v." + this.getDescription().getVersion());
        plugin = this;
        MagmaCore.onEnable(this);
        MagmaCore.startInitialization(this,
                new PluginInitializationConfig("ResourcePackManager", null, 10),
                this::asyncInitialization,
                this::syncInitialization,
                () -> Logger.info("ResourcePackManager fully initialized!"),
                throwable -> throwable.printStackTrace());
    }

    @Override
    public void onLoad() {
        MagmaCore.createInstance(this);
    }

    @Override
    public void onDisable() {
        MagmaCore.requestInitializationShutdown(this);
        if (MagmaCore.getInitializationState(this.getName()) == PluginInitializationState.INITIALIZING) {
            Logger.info("Disabling ResourcePackManager during initialization");
            ThirdPartyResourcePack.shutdown();
            AutoHost.shutdown();
            HandlerList.unregisterAll(this);
            MagmaCore.shutdown(this);
            return;
        }
        Logger.info("Disabling ResourcePackManager");
        ThirdPartyResourcePack.shutdown();
        AutoHost.shutdown();
        HandlerList.unregisterAll(this);
        MagmaCore.shutdown(this);
    }

    private void asyncInitialization(PluginInitializationContext initializationContext) {
        initializationContext.step("Data Config");
        new DataConfig();

        initializationContext.step("Default Config");
        new DefaultConfig();

        initializationContext.step("ItemsAdder Config");
        new ItemsAdderDismissedConfig();

        initializationContext.step("Mixer Folder");
        File mixerFolder = new File(getDataFolder(), "mixer");
        if (!mixerFolder.exists()) {
            mixerFolder.mkdirs();
        }

        initializationContext.step("Blueprint Folder");
        BlueprintFolder.initialize();

        initializationContext.step("Compatible Plugins");
        new CompatiblePluginConfig();

        initializationContext.step("Pack Integrations");
        for (CompatiblePluginConfigFields compatiblePluginConfigFields : CompatiblePluginConfig.getCompatiblePlugins().values()) {
            if (!compatiblePluginConfigFields.isEnabled()) continue;
            ThirdPartyResourcePack.initializeThirdPartyResourcePack(compatiblePluginConfigFields);
        }
    }

    private void syncInitialization(PluginInitializationContext initializationContext) {
        initializationContext.step("Change Watchdog");
        ThirdPartyResourcePack.startResourcePackChangeWatchdog();

        initializationContext.step("Event Listeners");
        if (DefaultConfig.isAutoHost()) {
            Bukkit.getPluginManager().registerEvents(new PlayerManager(), this);
        }
        Bukkit.getPluginManager().registerEvents(new VersionChecker.VersionCheckerEvents(), this);
        Bukkit.getPluginManager().registerEvents(new ItemsAdderWarningListener(), this);

        initializationContext.step("Commands");
        CommandManager commandManager = new CommandManager(this, "resourcepackmanager");
        commandManager.registerCommand(new ReloadCommand());
        commandManager.registerCommand(new DataComplianceRequestCommand());
        commandManager.registerCommand(new ItemsAdderCommand());

        initializationContext.step("Metrics");
        new Metrics(this, 22867);

        initializationContext.step("Version Check");
        MagmaCore.checkVersionUpdate("118574", "https://www.spigotmc.org/resources/resource-pack-manager.118574/");
    }
}
