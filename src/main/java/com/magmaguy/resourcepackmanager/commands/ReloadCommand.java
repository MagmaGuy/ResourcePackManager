package com.magmaguy.resourcepackmanager.commands;

import com.magmaguy.resourcepackmanager.Logger;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.util.List;

public class ReloadCommand extends AdvancedCommand {
    public ReloadCommand() {
        super(List.of("reload"), "Reloads the plugin", "*", false, "/fmm reload");
    }

    public static void reloadPlugin(CommandSender sender) {
        ResourcePackManager.plugin.onDisable();
        ResourcePackManager.plugin.onEnable();
        sender.sendMessage("[ResourcePackManager] Reloaded plugin!");
    }

    @Override
    public void execute(CommandSender sender, String[] arguments) {
        reloadPlugin(sender);
    }

    @Override
    public List<String> onTabComplete(CommandSender commandSender, Command command, String label, String[] args) {
        return null;
    }
}