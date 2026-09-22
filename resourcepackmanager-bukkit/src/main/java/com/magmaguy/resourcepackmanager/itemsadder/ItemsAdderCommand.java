package com.magmaguy.resourcepackmanager.itemsadder;

import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.arguments.ListStringCommandArgument;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.thirdparty.ThirdPartyResourcePack;
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
    private boolean configurationPending;
    private long configurationRequest;
    private Runnable cancelPendingPublication = () -> { };

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
        if (configurationPending) {
            Logger.sendMessage(sender, "&eItemsAdder configuration is already pending.");
            return;
        }
        if (!ItemsAdderDetector.isItemsAdderInstalled()) {
            Logger.sendMessage(sender, "&cItemsAdder is not installed!");
            return;
        }

        File configFile = ItemsAdderDetector.getItemsAdderConfigFile();
        if (configFile == null || !configFile.exists()) {
            Logger.sendMessage(sender, "&cCould not find ItemsAdder config.yml!");
            return;
        }

        try {
            byte[] original = java.nio.file.Files.readAllBytes(configFile.toPath());
            YamlConfiguration config = new YamlConfiguration();
            config.loadFromString(new String(original, java.nio.charset.StandardCharsets.UTF_8));
            if (ItemsAdderDetector.isItemsAdderHosting(config)) {
                Logger.sendMessage(sender, "&eItemsAdder is already hosting its pack. Disable its hosting before configuring RSPM.");
                return;
            }

            // Set no-host enabled
            config.set("resource-pack.hosting.no-host.enabled", true);

            // Disable all protections
            config.set("resource-pack.zip.protect-file-from-unzip.protection_1", false);
            config.set("resource-pack.zip.protect-file-from-unzip.protection_2", false);
            config.set("resource-pack.zip.protect-file-from-unzip.protection_3", false);

            // Keep generated model JSON parseable for RSPM's merge + Bedrock conversion.
            config.set("resource-pack.zip.compress-json-files", false);

            // Save the config
            java.nio.file.Path pending = java.nio.file.Files.createTempFile(
                    configFile.toPath().toAbsolutePath().getParent(), ".rspm-config-", ".tmp");
            try {
                config.save(pending.toFile());
                if (!java.util.Arrays.equals(original, java.nio.file.Files.readAllBytes(configFile.toPath()))) {
                    throw new java.io.IOException("ItemsAdder config changed during configuration; retry the command.");
                }
                com.magmaguy.resourcepackmanager.mixer.engine.internal.ZipUtil.publishAtomically(pending, configFile.toPath());
            } finally {
                java.nio.file.Files.deleteIfExists(pending);
            }
            configurationPending = true;
            long request = ++configurationRequest;

            Logger.sendMessage(sender, "&aItemsAdder configuration updated successfully!");
            Logger.sendMessage(sender, "&7- Enabled no-host mode");
            Logger.sendMessage(sender, "&7- Disabled file protections");
            Logger.sendMessage(sender, "&7- Disabled JSON compression");
            Logger.sendMessage(sender, "");
            Logger.sendMessage(sender, "&eReloading ItemsAdder...");

            // Reload ItemsAdder first, then RSPM
            new BukkitRunnable() {
                @Override
                public void run() {
                    try {
                        if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "iareload")) {
                            throw new IllegalStateException("ItemsAdder refused /iareload");
                        }
                        Logger.sendMessage(sender, "&aItemsAdder reload requested!");
                    } catch (Exception e) {
                        Logger.sendMessage(sender, "&cFailed to reload ItemsAdder: " + e.getMessage());
                        Logger.sendMessage(sender, "&7Try running /iareload manually.");
                        configurationPending = false;
                        return;
                    }

                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            try {
                                cancelPendingPublication = ThirdPartyResourcePack.awaitNextSourcePublication("ItemsAdder", success -> {
                                    if (configurationRequest != request) return;
                                    configurationPending = false;
                                    Logger.sendMessage(sender, success
                                            ? "&aRSPM merged the regenerated ItemsAdder export. Use /rspm status to check hosting."
                                            : "&cThe regenerated ItemsAdder export could not be merged. Check the console; RSPM will retry.");
                                });
                                if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "iazip")) {
                                    throw new IllegalStateException("ItemsAdder refused /iazip");
                                }
                                Logger.sendMessage(sender, "&aItemsAdder zip regeneration requested!");
                                Logger.sendMessage(sender, "&7RSPM will detect and merge the completed export. Use /rspm status to check hosting.");
                            } catch (Exception e) {
                                Logger.sendMessage(sender, "&cFailed to regenerate ItemsAdder zip: " + e.getMessage());
                                Logger.sendMessage(sender, "&7Try running /iazip manually.");
                                cancelPendingPublication.run();
                                if (configurationRequest == request) configurationPending = false;
                            }
                        }
                    }.runTaskLater(ResourcePackManager.plugin, 100L);

                }
            }.runTaskLater(ResourcePackManager.plugin, 20L); // 1 second delay

        } catch (Exception e) {
            configurationPending = false;
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
