package com.magmaguy.resourcepackmanager.commands;

import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.autohost.AutoHost;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;

public class DataComplianceRequestCommand extends AdvancedCommand {

    public DataComplianceRequestCommand() {
        super(List.of("data_compliance_request"));
        setDescription("Downloads a copy of all data associated to this server from the autohoster");
        setPermission("resourcepackmanager.*");
        setUsage("/fmm data_compliance_request");
    }

    @Override
    public void execute(CommandData commandData) {
        if (AutoHost.getRspUUID() == null) {
            Logger.sendMessage(commandData.getCommandSender(), "Seems like the auto-hoster is either disabled or not working, no data is stored in remote servers because no connection to remote servers is established");
            return;
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    AutoHost.dataComplianceRequest();
                    Logger.sendMessage(commandData.getCommandSender(), "Data compliance request completed, check ~/plugins/ResourcePackManager/data_compliance to see all data the remote server has stored for this server.");
                } catch (Exception e) {
                    Logger.sendMessage(commandData.getCommandSender(), "Failed to request data, check console for error logs!");
                    e.printStackTrace();
                }
            }
        }.runTaskAsynchronously(ResourcePackManager.plugin);
    }
}
