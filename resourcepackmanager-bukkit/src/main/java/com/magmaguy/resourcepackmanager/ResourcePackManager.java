package com.magmaguy.resourcepackmanager;

import com.magmaguy.magmacore.MagmaCore;
import com.magmaguy.magmacore.command.CommandManager;
import com.magmaguy.magmacore.initialization.PluginInitializationConfig;
import com.magmaguy.magmacore.initialization.PluginInitializationContext;
import com.magmaguy.magmacore.initialization.PluginInitializationState;
import com.magmaguy.magmacore.nightbreak.NightbreakPluginBootstrap;
import com.magmaguy.magmacore.nightbreak.NightbreakPluginHooks;
import com.magmaguy.magmacore.nightbreak.NightbreakPluginSpec;
import com.magmaguy.magmacore.nightbreak.NightbreakPluginUpdater;
import com.magmaguy.magmacore.nightbreak.NightbreakSetupControls;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.autohost.AutoHost;
import com.magmaguy.resourcepackmanager.bedrock.BedrockConversion;
import com.magmaguy.resourcepackmanager.bedrock.BukkitBedrockConverterContext;
import com.magmaguy.resourcepackmanager.bedrock.GeyserPackProvider;
import com.magmaguy.resourcepackmanager.bedrock.bridge.GeyserBridgeInstaller;
import com.magmaguy.resourcepackmanager.commands.DataComplianceRequestCommand;
import com.magmaguy.resourcepackmanager.commands.ReloadCommand;
import com.magmaguy.resourcepackmanager.commands.StatusCommand;
import com.magmaguy.resourcepackmanager.commands.VerboseLoggingCommand;
import com.magmaguy.resourcepackmanager.config.BedrockDisplayOffsetsConfig;
import com.magmaguy.resourcepackmanager.config.BlueprintFolder;
import com.magmaguy.resourcepackmanager.itemsadder.ItemsAdderCommand;
import com.magmaguy.resourcepackmanager.itemsadder.ItemsAdderDismissedConfig;
import com.magmaguy.resourcepackmanager.itemsadder.ItemsAdderWarningListener;
import com.magmaguy.resourcepackmanager.config.DataConfig;
import com.magmaguy.resourcepackmanager.config.DefaultConfig;
import com.magmaguy.resourcepackmanager.config.compatibleplugins.CompatiblePluginConfig;
import com.magmaguy.resourcepackmanager.config.compatibleplugins.CompatiblePluginConfigFields;
import com.magmaguy.resourcepackmanager.mixer.Mix;
import com.magmaguy.resourcepackmanager.playermanager.PlayerManager;
import com.magmaguy.resourcepackmanager.thirdparty.ThirdPartyResourcePack;
import com.magmaguy.resourcepackmanager.update.BackendPluginUpdateArtifactProvider;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public class ResourcePackManager extends JavaPlugin {

    public static final NightbreakPluginSpec NIGHTBREAK_PLUGIN_SPEC = new NightbreakPluginSpec(
            "ResourcePackManager", "resourcepackmanager", "resourcepackmanager.*",
            "resourcepackmanager.setup", "resourcepackmanager.initialize",
            "", "Reloaded ResourcePackManager.",
            false, false, false);

    public static JavaPlugin plugin;
    private NightbreakPluginUpdater.ListenerRegistration pluginUpdateListener;

    /**
     * The jar this plugin is running from; JavaPlugin#getFile() is protected,
     * and the proxy install assist needs it to stage a byte-identical copy.
     */
    public java.io.File pluginJarFile() {
        return getFile();
    }

    /**
     * Covers the /reload case: ops already online get no fresh join event, so
     * re-surface the proxy-missing-RSPM banner to any online op directly.
     */
    private void warnOnlineOpsIfProxyLinkMissing() {
        if (!com.magmaguy.resourcepackmanager.network.ProxyLinkWarning.bedrockProxyLinkMissing()) return;
        getServer().getOnlinePlayers().stream()
                .filter(org.bukkit.entity.Player::isOp)
                .forEach(com.magmaguy.resourcepackmanager.network.ProxyLinkWarning::warnPlayer);
    }

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
        pluginUpdateListener = NightbreakPluginUpdater.onPluginUpdateDownloaded(
                this, result -> {
                    if (!BackendPluginUpdateArtifactProvider.recordDownloaded(
                            result.downloadedFile())) return;
                    Bukkit.getScheduler().runTask(this, () ->
                            GeyserBridgeInstaller.stageDownloadedUpdate(
                                    result.downloadedFile().toPath()));
                });

        // Load the small, local config surface needed to validate and host a
        // copied saved mix before MagmaCore waits for every soft dependency.
        // Plugin source discovery and staging still happen in the normal
        // dependency-aware initialization below.
        new DataConfig();
        new DefaultConfig();
        new CompatiblePluginConfig();

        NightbreakPluginBootstrap.startInitialization(this,
                new PluginInitializationConfig("ResourcePackManager", null, 10),
                NIGHTBREAK_PLUGIN_SPEC,
                new NightbreakPluginHooks() {
                    @Override
                    public void asyncInitialization(PluginInitializationContext initializationContext) {
                        ResourcePackManager.this.asyncInitialization(initializationContext);
                    }

                    @Override
                    public void syncInitialization(PluginInitializationContext initializationContext) {
                        ResourcePackManager.this.syncInitialization(initializationContext);
                    }

                    @Override
                    public void onInitializationSuccess() {
                        Logger.info("ResourcePackManager fully initialized!");
                    }

                    @Override
                    public void onInitializationFailure(Throwable throwable) {
                        throwable.printStackTrace();
                    }
                });
        Mix.publishVerifiedExistingMixAsync();
    }

    @Override
    public void onLoad() {
        MagmaCore.createInstance(this);
    }

    @Override
    public void onDisable() {
        boolean shutdownDuringInitialization =
                MagmaCore.getInitializationState(this.getName())
                        == PluginInitializationState.INITIALIZING;
        MagmaCore.requestInitializationShutdown(this);
        Mix.cancelStartupReuse();
        if (pluginUpdateListener != null) {
            pluginUpdateListener.close();
            pluginUpdateListener = null;
        }
        Logger.info(shutdownDuringInitialization
                ? "Disabling ResourcePackManager during initialization"
                : "Disabling ResourcePackManager");
        ThirdPartyResourcePack.shutdown();
        AutoHost.shutdown();
        GeyserBridgeInstaller.unregister();
        com.magmaguy.resourcepackmanager.network.NetworkKeyProvisioning.unregister();
        GeyserPackProvider.unregister();
        HandlerList.unregisterAll(this);
        MagmaCore.shutdown(this);
    }

    private void asyncInitialization(PluginInitializationContext initializationContext) {
        initializationContext.step("Bedrock Display Offsets Config");
        new BedrockDisplayOffsetsConfig();

        initializationContext.step("Bedrock Mappings Pre-deploy");
        BedrockConversion.deployPreviousMappingsIfNeeded(new BukkitBedrockConverterContext());

        initializationContext.step("ItemsAdder Config");
        new ItemsAdderDismissedConfig();

        initializationContext.step("Mixer Folder");
        File mixerFolder = new File(getDataFolder(), "mixer");
        if (!mixerFolder.exists()) {
            mixerFolder.mkdirs();
        }

        initializationContext.step("Blueprint Folder");
        BlueprintFolder.initialize();

        initializationContext.step("Pack Integrations");
        for (CompatiblePluginConfigFields compatiblePluginConfigFields : CompatiblePluginConfig.getCompatiblePlugins().values()) {
            ThirdPartyResourcePack.initializeThirdPartyResourcePack(compatiblePluginConfigFields);
        }
    }

    private void syncInitialization(PluginInitializationContext initializationContext) {
        initializationContext.step("Change Watchdog");
        ThirdPartyResourcePack.startResourcePackChangeWatchdog();

        initializationContext.step("Event Listeners");
        if (DefaultConfig.isAutoHost() || DefaultConfig.isSelfHostForce()) {
            Bukkit.getPluginManager().registerEvents(new PlayerManager(), this);
        }
        Bukkit.getPluginManager().registerEvents(new ItemsAdderWarningListener(), this);
        // Self-gates on the proxy-missing-RSPM + Bedrock condition, so it is
        // cheap to register unconditionally and stays silent otherwise.
        Bukkit.getPluginManager().registerEvents(
                new com.magmaguy.resourcepackmanager.network.ProxyLinkWarningListener(), this);
        warnOnlineOpsIfProxyLinkMissing();

        initializationContext.step("Geyser Bridge");
        GeyserBridgeInstaller.register();

        // Behind a proxy this asks for the network key on the first player join. It is
        // a no-op on standalone servers and on backends that already hold a key.
        com.magmaguy.resourcepackmanager.network.NetworkKeyProvisioning.register();

        initializationContext.step("Geyser Pack Provider");
        GeyserPackProvider.register();

        initializationContext.step("Commands");
        CommandManager commandManager = new CommandManager(this, "resourcepackmanager");
        NightbreakPluginBootstrap.registerStandardCommands(this,
                commandManager,
                NIGHTBREAK_PLUGIN_SPEC,
                player -> NightbreakSetupControls.openPluginSetupShell(this, player, NIGHTBREAK_PLUGIN_SPEC),
                sender -> ReloadCommand.reloadPlugin(sender));
        commandManager.registerCommand(new ReloadCommand());
        commandManager.registerCommand(new VerboseLoggingCommand());
        commandManager.registerCommand(new DataComplianceRequestCommand());
        commandManager.registerCommand(new ItemsAdderCommand());
        commandManager.registerCommand(new StatusCommand());

        initializationContext.step("Metrics");
        new Metrics(this, 22867);

        initializationContext.step("Version Check");
        MagmaCore.checkVersionUpdate("118574", "https://nightbreak.io/plugin/resourcepackmanager/");
    }
}
