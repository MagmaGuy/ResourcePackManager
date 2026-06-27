package com.magmaguy.resourcepackmanager.itemsadder;

import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.resourcepackmanager.BukkitMockTestSupport;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemsAdderCommandTest extends BukkitMockTestSupport {

    @Test
    void dismissActionPersistsPlayerDismissal() {
        PlayerMock player = server.addPlayer("Tiago");
        new ItemsAdderDismissedConfig();
        ItemsAdderCommand command = new ItemsAdderCommand();

        command.execute(new CommandData(player, new String[]{"itemsadder", "dismiss"}, command));

        assertTrue(ItemsAdderDismissedConfig.hasDismissed(player.getUniqueId()));
        assertTrue(player.nextMessage().contains("dismissed permanently"));
    }
}
