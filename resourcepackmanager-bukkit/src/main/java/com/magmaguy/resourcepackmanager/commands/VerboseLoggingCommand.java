package com.magmaguy.resourcepackmanager.commands;

import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.arguments.ListStringCommandArgument;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.config.DefaultConfig;
import org.bukkit.command.CommandSender;

import java.util.List;

/** Enables, disables, or toggles RSPM's detailed diagnostic logging. */
public class VerboseLoggingCommand extends AdvancedCommand {

    public VerboseLoggingCommand() {
        super(List.of("verbose", "verboselogging"));
        setDescription("Toggle detailed RSPM diagnostic logging");
        setPermission("resourcepackmanager.*");
        setUsage("/rspm verbose [on|off]");
        addOptionalArgument("state",
                new ListStringCommandArgument(List.of("on", "off"), "[on|off]"));
    }

    @Override
    public void execute(CommandData commandData) {
        CommandSender sender = commandData.getCommandSender();
        String requestedState = commandData.getStringArgument("state");
        boolean enabled = requestedState == null
                ? !DefaultConfig.isVerboseLogging()
                : requestedState.equalsIgnoreCase("on");

        if (!DefaultConfig.setVerboseLogging(enabled)) {
            Logger.sendMessage(sender,
                    "&cCould not save verbose logging. Check the console and config.yml permissions.");
            return;
        }

        Logger.sendMessage(sender, enabled
                ? "&aVerbose logging enabled. Rejoin or run /rspm reload to capture a new pack delivery attempt."
                : "&eVerbose logging disabled.");
    }
}
