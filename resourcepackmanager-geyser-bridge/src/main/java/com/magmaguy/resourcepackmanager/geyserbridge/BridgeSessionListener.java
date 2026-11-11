package com.magmaguy.resourcepackmanager.geyserbridge;

import com.magmaguy.resourcepackmanager.bridge.BridgeChannels;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.PacketSendingEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundCustomPayloadPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundCustomPayloadPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Listens to one Bedrock session's connection to the Java server for the bridge: it announces the bridge channel
 * to the server and keeps the session's custom entity bindings, the Java entity ids that are drawn with a custom
 * Bedrock definition.
 *
 * <p>Geyser does not give its listeners packets in the order the server sent them. It queues entity spawns and
 * removals, logins and respawns on the session's tick loop, and hands a plugin message over at once on the network
 * thread. A model that hides its mob and shows it again sends a removal, then the binding, then the spawn. Handled
 * at once, the binding could be stored before the tick loop reached that removal, and the mob then spawned as the
 * plain mob. So everything this listener does to the bindings, and every bridge payload, runs through the
 * session's own queue, which puts it back in the server's order.</p>
 *
 * <p>A binding outlives its entity's removal and a respawn. The server stops tracking a mob for a viewer and tracks
 * it again without the model noticing: a viewer who leaves the mob's tracking range and returns, who dies and
 * respawns nearby, or whose pet teleports after them. No new binding follows, so the one held has to last. The
 * server never gives an entity id to a second entity while it runs, so a kept binding cannot land on another mob.
 * A login starts another server's ids, behind a proxy, and clears them all.</p>
 */
final class BridgeSessionListener extends SessionAdapter {
    private static final String REGISTER_CHANNEL = "minecraft:register";
    // The minecraft:register payload separates channel names with NUL characters.
    private static final String REGISTER_CHANNEL_SEPARATOR = String.valueOf((char) 0);

    private final Function<Session, Map<Integer, String>> bindingsOf;
    private final Function<Object, String> channelOf;
    private final Consumer<Runnable> inSessionOrder;
    private final Consumer<byte[]> bridgePayload;

    /**
     * @param bindingsOf     the session's bindings, or null when the packet's connection is not the session's
     *                       current one
     * @param channelOf      the channel of a custom payload packet
     * @param inSessionOrder runs a task among the packets Geyser has queued for the session
     * @param bridgePayload  handles one payload of the bridge channel
     */
    BridgeSessionListener(Function<Session, Map<Integer, String>> bindingsOf, Function<Object, String> channelOf,
                          Consumer<Runnable> inSessionOrder, Consumer<byte[]> bridgePayload) {
        this.bindingsOf = bindingsOf;
        this.channelOf = channelOf;
        this.inSessionOrder = inSessionOrder;
        this.bridgePayload = bridgePayload;
    }

    @Override
    public void packetSending(PacketSendingEvent event) {
        Packet packet = event.getPacket();
        if (packet instanceof ServerboundCustomPayloadPacket payloadPacket
                && REGISTER_CHANNEL.equals(channelOf.apply(payloadPacket))) {
            String channels = new String(payloadPacket.getData(), StandardCharsets.UTF_8);
            if (Arrays.asList(channels.split(REGISTER_CHANNEL_SEPARATOR)).contains(BridgeChannels.CUSTOM_ENTITY)) {
                return;
            }
            event.setPacket(payloadPacket.withData(
                    (channels + REGISTER_CHANNEL_SEPARATOR + BridgeChannels.CUSTOM_ENTITY).getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Override
    public void packetReceived(Session tcpSession, Packet packet) {
        Map<Integer, String> bindings = bindingsOf.apply(tcpSession);
        if (bindings == null) return;
        if (packet instanceof ClientboundLoginPacket) {
            inSessionOrder.accept(bindings::clear);
        } else if (packet instanceof ClientboundCustomPayloadPacket payloadPacket
                && BridgeChannels.CUSTOM_ENTITY.equals(channelOf.apply(payloadPacket))) {
            byte[] payload = payloadPacket.getData();
            inSessionOrder.accept(() -> bridgePayload.accept(payload));
        }
    }
}
