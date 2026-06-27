package com.magmaguy.resourcepackmanager.commands;

import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.resourcepackmanager.BukkitMockTestSupport;
import com.magmaguy.resourcepackmanager.autohost.AutoHost;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DataComplianceRequestCommandTest extends BukkitMockTestSupport {

    @BeforeEach
    void resetAutoHostSession() throws Exception {
        AutoHost.shutdown();
        setStaticField(AutoHost.class, "rspUUID", null);
    }

    @Test
    void noRemoteSessionExplainsThatNoHostedDataExists() {
        PlayerMock player = server.addPlayer("Tiago");
        DataComplianceRequestCommand command = new DataComplianceRequestCommand();

        command.execute(new CommandData(player, new String[]{}, command));

        assertTrue(player.nextMessage().contains("auto-hoster is either disabled or not working"));
    }

    @Test
    void statusCommandWritesOperatorDiagnosticDump() {
        PlayerMock player = server.addPlayer("Operator");
        StatusCommand command = new StatusCommand();

        command.execute(new CommandData(player, new String[]{"status"}, command));

        assertTrue(player.nextMessage().contains("RSPM Status"));
    }
}
