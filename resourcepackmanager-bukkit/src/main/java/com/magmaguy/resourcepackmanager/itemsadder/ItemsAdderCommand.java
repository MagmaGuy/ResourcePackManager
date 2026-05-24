package com.magmaguy.resourcepackmanager.itemsadder;

import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.arguments.ListStringCommandArgument;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.commands.ReloadCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.util.List;

/**
 * Command to handle ItemsAdder configuration and warning dismissal.
 */
public class ItemsAdderCommand extends AdvancedCommand {

    public ItemsAdderCommand() {
        super(List.of("itemsadder"));
        setDescription("Configure ItemsAdder integration");
        addArgument("action", new ListStringCommandArgument(List.of("configure", "dismiss"), "<configure/dismiss>"));
        setPermission("resourcepackmanager.*");
        setUsage("/rspm itemsadder <configure|dismiss>");
    }

    @Override
    public void execute(CommandData commandData) {
        CommandSender sender = commandData.getCommandSender();

        String action = commandData.getStringArgument("action");
        if (action == null || action.isEmpty()) {
            Logger.sendMessage(sender, "&cUsage: /rspm itemsadder <configure|dismiss>");
            return;
        }

        switch (action.toLowerCase()) {
            case "configure":
                handleConfigure(sender);
                break;
            case "dismiss":
                handleDismiss(sender);
                break;
            default:
                Logger.sendMessage(sender, "&cUnknown action. Use: /rspm itemsadder <configure|dismiss>");
        }
    }

    /**
     * Handle the configure action - modifies ItemsAdder config and reloads plugins.
     */
    private void handleConfigure(CommandSender sender) {
        if (!ItemsAdderDetector.isItemsAdderInstalled()) {
            Logger.sendMessage(sender, "&cItemsAdder is not installed!");
            return;
        }

        if (ItemsAdderDetector.isItemsAdderHosting()) {
            Logger.sendMessage(sender, "&eItemsAdder is already configured to host its own resource pack.");
            Logger.sendMessage(sender, "&7If you want ResourcePackManager to host instead, please manually disable ItemsAdder's hosting.");
            return;
        }

        File configFile = ItemsAdderDetector.getItemsAdderConfigFile();
        if (configFile == null || !configFile.exists()) {
            Logger.sendMessage(sender, "&cCould not find ItemsAdder config.yml!");
            return;
        }

        try {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);

            // Set no-host enabled
            config.set("resource-pack.hosting.no-host.enabled", true);

            // Disable all protections
            config.set("resource-pack.zip.protect-file-from-unzip.protection_1", false);
            config.set("resource-pack.zip.protect-file-from-unzip.protection_2", false);
            config.set("resource-pack.zip.protect-file-from-unzip.protection_3", false);

            // Save the config
            config.save(configFile);

            Logger.sendMessage(sender, "&aItemsAdder configuration updated successfully!");
            Logger.sendMessage(sender, "&7- Enabled no-host mode");
            Logger.sendMessage(sender, "&7- Disabled file protections");
            Logger.sendMessage(sender, "");
            Logger.sendMessage(sender, "&eReloading ItemsAdder...");

            // Reload ItemsAdder first, then RSPM
            new BukkitRunnable() {
                @Override
                public void run() {
                    // Reload ItemsAdder
                    try {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "iazip");
                        Logger.sendMessage(sender, "&aItemsAdder reloaded!");
                    } catch (Exception e) {
                        Logger.sendMessage(sender, "&cFailed to reload ItemsAdder: " + e.getMessage());
                        Logger.sendMessage(sender, "&7Try running /iazip manually.");
                    }

                    // Schedule RSPM reload after ItemsAdder has time to regenerate
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            Logger.sendMessage(sender, "&eReloading ResourcePackManager...");
                            ReloadCommand.reloadPlugin(sender);
                            Logger.sendMessage(sender, "&aConfiguration complete! ResourcePackManager is now hosting the merged resource pack.");
                        }
                    }.runTaskLater(ResourcePackManager.plugin, 100L); // 5 seconds to let IA regenerate
                }
            }.runTaskLater(ResourcePackManager.plugin, 20L); // 1 second delay

        } catch (Exception e) {
            Logger.sendMessage(sender, "&cFailed to update ItemsAdder config: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Handle the dismiss action - permanently dismisses the warning for the player.
     */
    private void handleDismiss(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            Logger.sendMessage(sender, "&cThis command can only be used by players!");
            return;
        }

        ItemsAdderDismissedConfig.setDismissed(player.getUniqueId(), true);
        Logger.sendMessage(sender, "&aItemsAdder configuration warning has been dismissed permanently.");
        Logger.sendMessage(sender, "&7You can run &e/rspm itemsadder configure &7at any time to set it up.");
    }
}
