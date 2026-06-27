package com.magmaguy.resourcepackmanager.autohost;

import com.magmaguy.resourcepackmanager.BukkitMockTestSupport;
import com.magmaguy.resourcepackmanager.config.DefaultConfig;
import com.magmaguy.resourcepackmanager.mixer.Mix;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoHostTest extends BukkitMockTestSupport {

    private static final UUID RESOURCE_PACK_UUID = UUID.fromString("a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d");

    @BeforeEach
    void resetAutoHostState() throws Exception {
        AutoHost.shutdown();
        setStaticField(AutoHost.class, "done", false);
        setStaticField(AutoHost.class, "selfHostedUrl", null);
        setStaticField(AutoHost.class, "rspUUID", null);
        resendAttempts().clear();
    }

    @Test
    void joiningBeforePackIsReadyGetsDelayedNotReadyWarning() {
        PlayerMock player = server.addPlayer("Tiago");

        AutoHost.scheduleJoinSend(player);

        server.getScheduler().performTicks(39);
        assertNull(player.nextMessage());

        server.getScheduler().performOneTick();

        String warning = player.nextMessage();
        assertTrue(warning.contains("[RSPM]"));
        assertTrue(warning.contains("Resource pack still building"));
        assertTrue(player.nextMessage().contains("automatically"));
    }

    @Test
    void sendResourcePackAppliesSelfHostedPackWhenReady() throws Exception {
        ResourcePackCapturingPlayer player = new ResourcePackCapturingPlayer(server, "Tiago");
        setStaticField(AutoHost.class, "done", true);
        setStaticField(AutoHost.class, "selfHostedUrl", "http://127.0.0.1:25566/rspm.zip");
        setStaticField(Mix.class, "finalSHA1Bytes", new byte[20]);
        setStaticField(DefaultConfig.class, "resourcePackPrompt", "Use the test pack?");
        setStaticField(DefaultConfig.class, "forceResourcePack", false);

        AutoHost.sendResourcePack(player);

        assertTrue(player.hasResourcePack());
        assertEquals(RESOURCE_PACK_UUID, player.resourcePackId);
        assertEquals("http://127.0.0.1:25566/rspm.zip", player.resourcePackUrl);
        assertEquals("Use the test pack?", player.resourcePackPrompt);
        assertFalse(player.forceResourcePack);
    }

    @Test
    void failedDownloadStatusSchedulesBoundedRetryWhenPackIsReady() throws Exception {
        ResourcePackCapturingPlayer player = new ResourcePackCapturingPlayer(server, "Tiago");
        setReadySelfHostedPack();

        AutoHost.handleResourcePackStatus(new PlayerResourcePackStatusEvent(
                player,
                RESOURCE_PACK_UUID,
                PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD));

        assertEquals(1, resendAttempts().get(player.getUniqueId()));
        assertFalse(player.hasResourcePack());

        server.getScheduler().performTicks(39);
        assertFalse(player.hasResourcePack());

        server.getScheduler().performOneTick();
        assertTrue(player.hasResourcePack());
    }

    @Test
    void declinedStatusClearsRetryStateAndDoesNotResend() throws Exception {
        ResourcePackCapturingPlayer player = new ResourcePackCapturingPlayer(server, "Tiago");
        setReadySelfHostedPack();
        resendAttempts().put(player.getUniqueId(), 2);

        AutoHost.handleResourcePackStatus(new PlayerResourcePackStatusEvent(
                player,
                RESOURCE_PACK_UUID,
                PlayerResourcePackStatusEvent.Status.DECLINED));

        assertFalse(resendAttempts().containsKey(player.getUniqueId()));
        server.getScheduler().performTicks(50);
        assertFalse(player.hasResourcePack());
    }

    private void setReadySelfHostedPack() throws Exception {
        setStaticField(AutoHost.class, "done", true);
        setStaticField(AutoHost.class, "selfHostedUrl", "http://127.0.0.1:25566/rspm.zip");
        setStaticField(Mix.class, "finalSHA1Bytes", new byte[20]);
        setStaticField(DefaultConfig.class, "resourcePackPrompt", "Use the test pack?");
        setStaticField(DefaultConfig.class, "forceResourcePack", false);
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, Integer> resendAttempts() throws Exception {
        return (Map<UUID, Integer>) getStaticField(AutoHost.class, "resendAttempts");
    }

    private static class ResourcePackCapturingPlayer extends PlayerMock {
        private boolean resourcePackSent;
        private UUID resourcePackId;
        private String resourcePackUrl;
        private String resourcePackPrompt;
        private boolean forceResourcePack;

        private ResourcePackCapturingPlayer(ServerMock server, String name) {
            super(server, name);
        }

        @Override
        public void addResourcePack(UUID id, String url, byte[] hash, String prompt, boolean force) {
            resourcePackSent = true;
            resourcePackId = id;
            resourcePackUrl = url;
            resourcePackPrompt = prompt;
            forceResourcePack = force;
        }

        @Override
        public void setResourcePack(String url, byte[] hash, String prompt, boolean force) {
            resourcePackSent = true;
            resourcePackUrl = url;
            resourcePackPrompt = prompt;
            forceResourcePack = force;
        }

        @Override
        public boolean hasResourcePack() {
            return resourcePackSent;
        }

        @Override
        public boolean isOnline() {
            return true;
        }
    }
}
