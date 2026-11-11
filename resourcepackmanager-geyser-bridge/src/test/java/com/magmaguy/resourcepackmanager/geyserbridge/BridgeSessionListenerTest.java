package com.magmaguy.resourcepackmanager.geyserbridge;

import com.magmaguy.resourcepackmanager.bridge.BridgeChannels;
import com.magmaguy.resourcepackmanager.bridge.BridgeCodec;
import com.magmaguy.resourcepackmanager.bridge.BridgeMessage;
import com.magmaguy.resourcepackmanager.bridge.BridgeMessageType;
import net.kyori.adventure.key.Key;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerSpawnInfo;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundCustomPayloadPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRemoveEntitiesPacket;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The session's bindings under the order Geyser gives packets to its listeners. Geyser queues a packet whose
 * {@code shouldRunOnGameThread()} is true on the session's tick loop and hands any other packet over at once on the
 * network thread. Entity spawns, removals, logins and respawns are queued. Plugin messages are not, so a binding can
 * reach the listener before a removal the server sent ahead of it.
 */
class BridgeSessionListenerTest {
    private static final Key BRIDGE_CHANNEL = Key.key(BridgeChannels.CUSTOM_ENTITY);
    private static final String SEWER_RAT = "freeminecraftmodels:em_ag_sewerrat";
    private static final int MOB = 194;

    private final Deque<Runnable> tickLoop = new ArrayDeque<>();
    private boolean inTickLoop;
    private final Map<Integer, String> bindings = new ConcurrentHashMap<>();
    private final BridgeSessionListener listener = new BridgeSessionListener(
            connection -> bindings,
            packet -> ((ClientboundCustomPayloadPacket) packet).getChannel().asString(),
            this::ensureInTickLoop,
            this::handleBridgePayload);

    /** GeyserSession.ensureInEventLoop: at once for a caller on the tick loop, queued for any other. */
    private void ensureInTickLoop(Runnable task) {
        if (inTickLoop) task.run();
        else tickLoop.add(task);
    }

    /** What RspmGeyserBridgeCore.handleMessage does to the bindings. */
    private void handleBridgePayload(byte[] payload) {
        BridgeMessage message = BridgeCodec.decode(payload);
        if (message.type() == BridgeMessageType.SET_CUSTOM_ENTITY) bindings.put(message.entityId(), message.identifier());
    }

    /** NetworkSession.channelRead0: the server's packets, in the order the server sent them. */
    private void fromServer(Packet packet) {
        if (packet.shouldRunOnGameThread()) tickLoop.add(() -> listener.packetReceived(null, packet));
        else listener.packetReceived(null, packet);
    }

    /** The server spawns the mob for the viewer. Geyser asks for its definition when the tick loop gets there. */
    private AtomicReference<String> spawnFromServer(int entityId) {
        AtomicReference<String> drawnAs = new AtomicReference<>("never spawned");
        tickLoop.add(() -> drawnAs.set(bindings.get(entityId)));
        return drawnAs;
    }

    private void runTickLoop() {
        inTickLoop = true;
        try {
            while (!tickLoop.isEmpty()) tickLoop.poll().run();
        } finally {
            inTickLoop = false;
        }
    }

    private static Packet binding(int entityId, String identifier) {
        return new ClientboundCustomPayloadPacket(BRIDGE_CHANNEL,
                BridgeCodec.encode(BridgeMessage.setCustomEntity(entityId, identifier)));
    }

    private static Packet removal(int entityId) {
        return new ClientboundRemoveEntitiesPacket(new int[]{entityId});
    }

    private static PlayerSpawnInfo overworld() {
        return new PlayerSpawnInfo(0, Key.key("minecraft:overworld"), 0L, GameMode.SURVIVAL, GameMode.SURVIVAL,
                false, false, null, 0, 63);
    }

    private static Packet login() {
        return new ClientboundLoginPacket(1, false, new Key[]{Key.key("minecraft:overworld")}, 20, 10, 10,
                false, true, false, overworld(), true, false);
    }

    private static Packet respawn() {
        return new ClientboundRespawnPacket(overworld(), false, false);
    }

    @Test
    void aBindingSentAfterAnOlderRemovalStillReachesTheSpawn() {
        // A mob spawned and given a model in one tick: the server pairs it, the model hides it, and ticks later the
        // binding and the second spawn follow. The viewer's tick loop has not reached the removal yet.
        fromServer(removal(MOB));
        fromServer(binding(MOB, SEWER_RAT));
        AtomicReference<String> drawnAs = spawnFromServer(MOB);

        runTickLoop();

        assertEquals(SEWER_RAT, drawnAs.get(), "The removal was sent before the binding, so it must not undo it");
    }

    @Test
    void aBindingSentAfterALoginIsNotClearedByIt() {
        fromServer(login());
        fromServer(binding(MOB, SEWER_RAT));
        AtomicReference<String> drawnAs = spawnFromServer(MOB);

        runTickLoop();

        assertEquals(SEWER_RAT, drawnAs.get(), "The login was sent before the binding, so it must not clear it");
    }

    @Test
    void aBindingSurvivesItsEntitysRemoval() {
        // The server stops tracking the mob for the viewer and tracks it again, such as a viewer who walks out of
        // the mob's tracking range and back. The model still counts the viewer, so no new binding is sent.
        fromServer(binding(MOB, SEWER_RAT));
        runTickLoop();
        fromServer(removal(MOB));
        AtomicReference<String> drawnAs = spawnFromServer(MOB);

        runTickLoop();

        assertEquals(SEWER_RAT, drawnAs.get(), "A mob the server pairs again is still the custom entity");
    }

    @Test
    void aBindingSurvivesARespawn() {
        // A viewer who dies and respawns near the mob: the server removes every entity, sends the respawn and
        // pairs the mob again. The model still counts the viewer, so no new binding is sent.
        fromServer(binding(MOB, SEWER_RAT));
        runTickLoop();
        fromServer(removal(MOB));
        fromServer(respawn());
        AtomicReference<String> drawnAs = spawnFromServer(MOB);

        runTickLoop();

        assertEquals(SEWER_RAT, drawnAs.get(), "A mob the server pairs again after a respawn is still the custom entity");
    }

    @Test
    void aLoginClearsTheBindingsOfTheServerBefore() {
        // Behind a proxy the next server counts its entity ids from its own start, so an old binding could land on
        // an unrelated entity.
        fromServer(binding(MOB, SEWER_RAT));
        runTickLoop();
        fromServer(login());
        AtomicReference<String> drawnAs = spawnFromServer(MOB);

        runTickLoop();

        assertNull(drawnAs.get(), "An entity id of the server before is no custom entity on the next");
    }
}
