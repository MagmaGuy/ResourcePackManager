package com.magmaguy.resourcepackmanager.playermanager;

import com.magmaguy.resourcepackmanager.BukkitMockTestSupport;
import com.magmaguy.resourcepackmanager.autohost.AutoHost;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerManagerTest extends BukkitMockTestSupport {

    @BeforeEach
    void resetAutoHostState() throws Exception {
        AutoHost.shutdown();
        setStaticField(AutoHost.class, "done", false);
        setStaticField(AutoHost.class, "selfHostedUrl", null);
        setStaticField(AutoHost.class, "rspUUID", null);
    }

    @Test
    void joinEventDefersPackSendAndWarnsIfPackIsStillBuilding() {
        PlayerMock player = server.addPlayer("Tiago");

        new PlayerManager().onPlayerJoin(new PlayerJoinEvent(player, "joined"));

        server.getScheduler().performTicks(39);
        assertNull(player.nextMessage());

        server.getScheduler().performOneTick();
        String warning = player.nextMessage();

        assertTrue(warning.contains("[RSPM]"));
        assertTrue(warning.contains("Resource pack still building"));
        assertTrue(player.nextMessage().contains("automatically"));
    }
}
