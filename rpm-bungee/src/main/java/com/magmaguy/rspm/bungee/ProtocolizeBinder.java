package com.magmaguy.rspm.bungee;

import com.magmaguy.rspm.proxy.MergedPack;
import com.magmaguy.rspm.proxy.ProxyLogger;
import dev.simplix.protocolize.api.PacketDirection;
import dev.simplix.protocolize.api.Protocol;
import dev.simplix.protocolize.api.Protocolize;
import dev.simplix.protocolize.api.mapping.AbstractProtocolMapping;
import dev.simplix.protocolize.api.mapping.ProtocolIdMapping;
import dev.simplix.protocolize.api.packet.AbstractPacket;
import dev.simplix.protocolize.api.player.ProtocolizePlayer;
import dev.simplix.protocolize.api.util.ProtocolUtil;
import io.netty.buffer.ByteBuf;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static dev.simplix.protocolize.api.util.ProtocolVersions.*;

/**
 * BungeeCord/Waterfall Java resource-pack push, driven by Protocolize.
 *
 * <p>Protocolize ships with no built-in {@code ResourcePackSend} packet, so we
 * declare one ourselves (see {@link ResourcePackSendPacket}) and register it
 * for the protocol versions we want to support. On {@link PostLoginEvent} we
 * grab the player's {@link ProtocolizePlayer} handle and write the packet
 * with the current merged-pack URL and SHA-1 hash. Protocolize handles the
 * version-specific encoding via the mapping table below.</p>
 *
 * <p>Packet IDs (PLAY and CONFIGURATION states) were taken from Phoenix616's
 * ResourcepacksPlugins {@code packetmap.yml}. We support 1.8 through latest;
 * for the 1.20.3+ "push" variant the packet includes a leading UUID so the
 * client can de-duplicate.</p>
 *
 * <p>If Protocolize isn't on the proxy, this class is never instantiated —
 * see {@link RspmBungeePlugin#onEnable()}.</p>
 */
public final class ProtocolizeBinder implements Listener {

    private final ProxyLogger logger;
    private final boolean forceResourcePack;
    private volatile MergedPack current;

    public ProtocolizeBinder(ProxyLogger logger, boolean forceResourcePack) {
        this.logger = logger;
        this.forceResourcePack = forceResourcePack;
        registerPackets();
    }

    private void registerPackets() {
        try {
            Protocolize.protocolRegistration().registerPacket(
                    ResourcePackSendPacket.PLAY_MAPPINGS,
                    Protocol.PLAY,
                    PacketDirection.CLIENTBOUND,
                    ResourcePackSendPacket.class);
            // CONFIGURATION state was added in 1.20.2; the pack can also be pushed there.
            Protocolize.protocolRegistration().registerPacket(
                    ResourcePackSendPacket.CONFIGURATION_MAPPINGS,
                    Protocol.CONFIGURATION,
                    PacketDirection.CLIENTBOUND,
                    ResourcePackSendPacket.class);
            logger.info("[RSPM] ResourcePackSendPacket registered via Protocolize.");
        } catch (Throwable t) {
            logger.warn("[RSPM] Failed to register ResourcePackSendPacket via Protocolize", t);
        }
    }

    public void onMergedPackReady(MergedPack pack) {
        this.current = pack;
    }

    @EventHandler
    public void onPostLogin(PostLoginEvent event) {
        MergedPack pack = current;
        if (pack == null) return;
        UUID playerUuid = event.getPlayer().getUniqueId();
        try {
            ProtocolizePlayer protocolizePlayer = Protocolize.playerProvider().player(playerUuid);
            if (protocolizePlayer == null) {
                logger.warn("[RSPM] Protocolize hasn't tracked player "
                        + event.getPlayer().getName() + " yet; skipping Java pack push.");
                return;
            }
            ResourcePackSendPacket packet = new ResourcePackSendPacket(
                    pack.packUuid(),
                    pack.url(),
                    pack.sha1Hex().toLowerCase(),
                    forceResourcePack);
            protocolizePlayer.sendPacket(packet);
        } catch (Throwable t) {
            logger.warn("[RSPM] Failed to send Java resource pack to "
                    + event.getPlayer().getName(), t);
        }
    }

    /**
     * Custom {@link AbstractPacket} implementation for clientbound resource
     * pack push. Protocolize doesn't bundle one, so we provide our own.
     *
     * <p>Wire format (per wiki.vg):</p>
     * <ul>
     *   <li>&lt; 1.17: VarInt prefix + url (String) + hash (String)</li>
     *   <li>1.17 - 1.20.2: + required (Bool) + hasPromptMessage (Bool)
     *       [+ promptMessage (Chat)]</li>
     *   <li>1.20.3+: leading UUID (de-dup id) + url + hash + required + prompt</li>
     * </ul>
     *
     * <p>We never send a prompt message — write {@code false} for that flag.</p>
     */
    public static final class ResourcePackSendPacket extends AbstractPacket {

        /**
         * Packet IDs in PLAY state across protocol versions. Source:
         * Phoenix616 ResourcepacksPlugins {@code packetmap.yml}, cross-checked
         * with wiki.vg. Each range covers contiguous versions sharing one ID.
         */
        public static final List<ProtocolIdMapping> PLAY_MAPPINGS = Arrays.asList(
                AbstractProtocolMapping.rangedIdMapping(MINECRAFT_1_8, MINECRAFT_1_8, 0x48),
                AbstractProtocolMapping.rangedIdMapping(MINECRAFT_1_9, MINECRAFT_1_11_2, 0x32),
                AbstractProtocolMapping.rangedIdMapping(MINECRAFT_1_12, MINECRAFT_1_12, 0x33),
                AbstractProtocolMapping.rangedIdMapping(MINECRAFT_1_12_1, MINECRAFT_1_12_2, 0x34),
                AbstractProtocolMapping.rangedIdMapping(MINECRAFT_1_13, MINECRAFT_1_13_2, 0x37),
                AbstractProtocolMapping.rangedIdMapping(MINECRAFT_1_14, MINECRAFT_1_14_4, 0x39),
                AbstractProtocolMapping.rangedIdMapping(MINECRAFT_1_15, MINECRAFT_1_15_2, 0x3A),
                AbstractProtocolMapping.rangedIdMapping(MINECRAFT_1_16, MINECRAFT_1_16_1, 0x39),
                AbstractProtocolMapping.rangedIdMapping(MINECRAFT_1_16_2, MINECRAFT_1_16_5, 0x38),
                AbstractProtocolMapping.rangedIdMapping(MINECRAFT_1_17, MINECRAFT_1_18_2, 0x3C),
                AbstractProtocolMapping.rangedIdMapping(MINECRAFT_1_19, MINECRAFT_1_19, 0x3A),
                AbstractProtocolMapping.rangedIdMapping(MINECRAFT_1_19_1, MINECRAFT_1_19_2, 0x3D),
                AbstractProtocolMapping.rangedIdMapping(MINECRAFT_1_19_3, MINECRAFT_1_19_3, 0x3C),
                AbstractProtocolMapping.rangedIdMapping(MINECRAFT_1_19_4, MINECRAFT_1_20_1, 0x40),
                AbstractProtocolMapping.rangedIdMapping(MINECRAFT_1_20_2, MINECRAFT_1_20_2, 0x42),
                AbstractProtocolMapping.rangedIdMapping(MINECRAFT_1_20_3, MINECRAFT_1_20_4, 0x44),
                AbstractProtocolMapping.rangedIdMapping(MINECRAFT_1_20_5, MINECRAFT_1_21_1, 0x46),
                AbstractProtocolMapping.rangedIdMapping(MINECRAFT_1_21_2, MINECRAFT_1_21_3, 0x4B),
                AbstractProtocolMapping.rangedIdMapping(MINECRAFT_1_21_4, MINECRAFT_LATEST, 0x4A)
                // Minecraft 1.21.4 (protocol 769) renumbered the clientbound
                // resource-pack-push packet to 0x4A in PLAY. Protocolize 2.4.3
                // exposes MINECRAFT_1_21_4 and bumps MINECRAFT_LATEST to 769.
        );

        /**
         * Packet IDs in CONFIGURATION state. This state was added in 1.20.2;
         * sending the pack here means the client gets it before the world
         * loads. Earlier versions don't have a CONFIGURATION state at all.
         */
        public static final List<ProtocolIdMapping> CONFIGURATION_MAPPINGS = Arrays.asList(
                AbstractProtocolMapping.rangedIdMapping(MINECRAFT_1_20_2, MINECRAFT_1_20_2, 0x06),
                AbstractProtocolMapping.rangedIdMapping(MINECRAFT_1_20_3, MINECRAFT_1_20_4, 0x07),
                AbstractProtocolMapping.rangedIdMapping(MINECRAFT_1_20_5, MINECRAFT_LATEST, 0x09)
        );

        private UUID uuid;
        private String url;
        private String hash;
        private boolean required;

        public ResourcePackSendPacket() {
            // Required no-arg ctor for Protocolize to instantiate when reading inbound packets
            // (we never read inbound for clientbound, but the framework still constructs it).
        }

        public ResourcePackSendPacket(UUID uuid, String url, String hash, boolean required) {
            this.uuid = uuid;
            this.url = url;
            this.hash = hash;
            this.required = required;
        }

        @Override
        public void read(ByteBuf buf, PacketDirection direction, int protocolVersion) {
            // CLIENTBOUND only — we never decode this packet. If Protocolize hands
            // us an inbound read for some reason, swallow the buffer.
            if (protocolVersion >= MINECRAFT_1_20_3) {
                this.uuid = new UUID(buf.readLong(), buf.readLong());
            }
            this.url = ProtocolUtil.readString(buf);
            this.hash = ProtocolUtil.readString(buf);
            if (protocolVersion >= MINECRAFT_1_17) {
                this.required = buf.readBoolean();
                if (buf.readBoolean()) {
                    // Drain the optional prompt-message Chat component.
                    ProtocolUtil.readString(buf);
                }
            }
        }

        @Override
        public void write(ByteBuf buf, PacketDirection direction, int protocolVersion) {
            if (protocolVersion >= MINECRAFT_1_20_3) {
                UUID id = uuid != null ? uuid : UUID.nameUUIDFromBytes(url.getBytes());
                buf.writeLong(id.getMostSignificantBits());
                buf.writeLong(id.getLeastSignificantBits());
            }
            ProtocolUtil.writeString(buf, url);
            ProtocolUtil.writeString(buf, hash == null ? "" : hash);
            if (protocolVersion >= MINECRAFT_1_17) {
                buf.writeBoolean(required);
                // No prompt message.
                buf.writeBoolean(false);
            }
        }
    }
}
