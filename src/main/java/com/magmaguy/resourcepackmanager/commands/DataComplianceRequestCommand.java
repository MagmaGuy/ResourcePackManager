package com.magmaguy.resourcepackmanager.commands;

import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.autohost.AutoHost;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;

public class DataComplianceRequestCommand extends AdvancedCommand {

    public DataComplianceRequestCommand() {
        super(List.of("data_compliance_request"), "Downloads a copy of all data associated to this server from the autohoster", "*", false, "/fmm data_compliance_request");
    }

    @Override
    public void execute(CommandSender sender, String[] arguments) {
        if (AutoHost.getRspUUID() == null) {
            sender.sendMessage("[ResourcePackManager] Seems like the auto-hoster is either disabled or not working, no data is stored in remote servers because no connection to remote servers is established");
            return;
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    AutoHost.dataComplianceRequest();
                    sender.sendMessage("[ResourcePackManager] Data compliance request completed, check ~/plugins/ResourcePackManager/data_compliance to see all data the remote server has stored for this server.");
                } catch (Exception e) {
                    sender.sendMessage("[ResourcePackManager] Failed to request data, check console for error logs!");
                    e.printStackTrace();
                }
            }
        }.runTaskAsynchronously(ResourcePackManager.plugin);

    }

    @Override
    public List<String> onTabComplete(CommandSender commandSender, Command command, String label, String[] args) {
        return List.of();
    }
}
