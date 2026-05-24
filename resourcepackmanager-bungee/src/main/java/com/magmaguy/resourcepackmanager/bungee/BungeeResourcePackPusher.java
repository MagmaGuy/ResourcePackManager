package com.magmaguy.resourcepackmanager.bungee;

import com.magmaguy.resourcepackmanager.proxy.MergedPack;
import com.magmaguy.resourcepackmanager.proxy.ProxyLogger;
import io.netty.buffer.ByteBuf;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.protocol.AbstractPacketHandler;
import net.md_5.bungee.protocol.DefinedPacket;
import net.md_5.bungee.protocol.Protocol;
import net.md_5.bungee.protocol.ProtocolConstants;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * BungeeCord / Waterfall Java resource-pack push, using BungeeCord's native
 * protocol API directly (no Protocolize).
 *
 * <p>Protocolize 2.4.3's Bungee bridge crashes on modern BungeeCord 1.21.4+
 * (build 26.1) and current Waterfall: its internal packet-registration code
 * casts BungeeCord's {@code Protocol$DirectionData.protocols} map to
 * {@code gnu.trove.map.TIntObjectMap}, but Bungee migrated the field to
 * {@code it.unimi.dsi.fastutil.ints.Int2ObjectMap}. The cast throws
 * {@link ClassCastException} at plugin enable, packet registration never
 * completes, and Java pack push silently fails.</p>
 *
 * <p>We dodge the broken Protocolize layer entirely by calling
 * {@code Protocol$DirectionData.registerPacket(Class, Supplier, ProtocolMapping[])}
 * via reflection — the same private method Bungee uses internally. Because we
 * invoke the same JVM-level setter Bungee itself uses, it works on whatever
 * map type Bungee currently holds (fastutil or Trove, both supported), giving
 * us the same forward-compatibility Bungee has for its own packets.</p>
 *
 * <p>Sending is done via the public {@code ProxiedPlayer#unsafe().sendPacket(DefinedPacket)}
 * API — no reflection needed for the hot path.</p>
 *
 * <p>Packet IDs (PLAY and CONFIGURATION states) come from Phoenix616's
 * ResourcepacksPlugins {@code packetmap.yml}, identical to what the old
 * Protocolize-backed code used; coverage is Minecraft 1.8 through 1.21.4+.</p>
 */
public final class BungeeResourcePackPusher implements Listener {

    // --- Protocol version constants (raw ints; Bungee's compiled-in classes don't ----------
    // expose newer ProtocolConstants.MINECRAFT_* for every build, so we hard-code).
    private static final int MC_1_8       = 47;
    private static final int MC_1_9       = 107;
    private static final int MC_1_11_2    = 316;
    private static final int MC_1_12      = 335;
    private static final int MC_1_12_1    = 338;
    private static final int MC_1_12_2    = 340;
    private static final int MC_1_13      = 393;
    private static final int MC_1_13_2    = 404;
    private static final int MC_1_14      = 477;
    private static final int MC_1_14_4    = 498;
    private static final int MC_1_15      = 573;
    private static final int MC_1_15_2    = 578;
    private static final int MC_1_16      = 735;
    private static final int MC_1_16_1    = 736;
    private static final int MC_1_16_2    = 751;
    private static final int MC_1_16_5    = 754;
    private static final int MC_1_17      = 755;
    private static final int MC_1_18_2    = 758;
    private static final int MC_1_19      = 759;
    private static final int MC_1_19_1    = 760;
    private static final int MC_1_19_2    = 760;
    private static final int MC_1_19_3    = 761;
    private static final int MC_1_19_4    = 762;
    private static final int MC_1_20_1    = 763;
    private static final int MC_1_20_2    = 764;
    private static final int MC_1_20_3    = 765;
    private static final int MC_1_20_4    = 765;
    private static final int MC_1_20_5    = 766;
    private static final int MC_1_21_1    = 767;
    private static final int MC_1_21_2    = 768;
    private static final int MC_1_21_3    = 768;
    private static final int MC_1_21_4    = 769;
    private static final int MC_LATEST    = 769;

    private static volatile boolean registered = false;

    private final ProxyLogger logger;
    private final boolean forceResourcePack;
    private volatile MergedPack current;

    public BungeeResourcePackPusher(ProxyLogger logger, boolean forceResourcePack) {
        this.logger = logger;
        this.forceResourcePack = forceResourcePack;
    }

    /**
     * Registers {@link ResourcePackPushPacket} against {@code Protocol.GAME.TO_CLIENT}
     * and {@code Protocol.CONFIGURATION.TO_CLIENT} via reflection. Idempotent —
     * safe to call multiple times; subsequent calls are no-ops.
     *
     * @throws IllegalStateException if BungeeCord's internal protocol API has
     *         changed shape in a way we can't accommodate.
     */
    public static synchronized void ensureRegistered() {
        if (registered) return;

        try {
            Class<?> directionDataClass = Class.forName("net.md_5.bungee.protocol.Protocol$DirectionData");
            Class<?> mappingClass = Class.forName("net.md_5.bungee.protocol.Protocol$ProtocolMapping");

            // BungeeCord's Protocol$DirectionData.registerPacket has shifted
            // shape between major versions:
            //   3-arg (older): (Class, Supplier, ProtocolMapping[])
            //   4-arg (26.1+ and 1.21.4+ snapshots): (Class, Supplier, RegisterType, ProtocolMapping[])
            // We support both. If both are somehow present we prefer the
            // 3-arg variant for symmetry with older code paths.
            Method registerPacket = null;
            boolean fourArg = false;
            for (Method m : directionDataClass.getDeclaredMethods()) {
                if (!m.getName().equals("registerPacket")) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params.length < 3) continue;
                if (params[0] != Class.class) continue;
                if (params[1] != Supplier.class) continue;
                if (params.length == 3 && params[2].isArray() && params[2].getComponentType() == mappingClass) {
                    registerPacket = m;
                    fourArg = false;
                    break; // prefer 3-arg
                }
                if (params.length == 4 && params[3].isArray() && params[3].getComponentType() == mappingClass) {
                    // capture but keep scanning in case a 3-arg overload also exists
                    registerPacket = m;
                    fourArg = true;
                }
            }
            if (registerPacket == null) {
                throw new IllegalStateException("No suitable registerPacket method found on " + directionDataClass
                        + ". BungeeCord internal API may have changed.");
            }
            registerPacket.setAccessible(true);

            // For the 4-arg form, resolve the RegisterType.ENCODE constant.
            // We only ever encode (clientbound, proxy -> client). Fall back
            // to BOTH, then to the first declared constant, in case a fork
            // renames things.
            Object registerType = null;
            if (fourArg) {
                Class<?> registerTypeClass = Class.forName("net.md_5.bungee.protocol.Protocol$RegisterType");
                Object[] values = registerTypeClass.getEnumConstants();
                if (values == null || values.length == 0) {
                    throw new IllegalStateException("Protocol$RegisterType has no enum constants");
                }
                for (Object v : values) {
                    if ("ENCODE".equals(((Enum<?>) v).name())) {
                        registerType = v;
                        break;
                    }
                }
                if (registerType == null) {
                    for (Object v : values) {
                        if ("BOTH".equals(((Enum<?>) v).name())) {
                            registerType = v;
                            break;
                        }
                    }
                }
                if (registerType == null) {
                    registerType = values[0];
                }
            }

            Constructor<?> mappingCtor = mappingClass.getDeclaredConstructor(int.class, int.class);
            mappingCtor.setAccessible(true);

            Object[] playMappings = buildMappings(mappingClass, mappingCtor, playEntries());
            Object[] configMappings = buildMappings(mappingClass, mappingCtor, configEntries());

            Supplier<DefinedPacket> supplier = ResourcePackPushPacket::new;

            Object playArray = toMappingArray(mappingClass, playMappings);
            Object configArray = toMappingArray(mappingClass, configMappings);

            Object gameToClient = Protocol.GAME.TO_CLIENT;
            Object configToClient = Protocol.CONFIGURATION.TO_CLIENT;

            if (fourArg) {
                registerPacket.invoke(gameToClient, ResourcePackPushPacket.class, supplier, registerType, playArray);
                registerPacket.invoke(configToClient, ResourcePackPushPacket.class, supplier, registerType, configArray);
            } else {
                registerPacket.invoke(gameToClient, ResourcePackPushPacket.class, supplier, playArray);
                registerPacket.invoke(configToClient, ResourcePackPushPacket.class, supplier, configArray);
            }

            registered = true;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to register RSPM resource-pack-push packet via native Bungee API", t);
        }
    }

    private static Object[] buildMappings(Class<?> mappingClass, Constructor<?> ctor, List<int[]> entries) throws Exception {
        Object[] arr = (Object[]) Array.newInstance(mappingClass, entries.size());
        for (int i = 0; i < entries.size(); i++) {
            int[] e = entries.get(i);
            arr[i] = ctor.newInstance(e[0], e[1]);
        }
        return arr;
    }

    private static Object toMappingArray(Class<?> mappingClass, Object[] mappings) {
        Object dest = Array.newInstance(mappingClass, mappings.length);
        for (int i = 0; i < mappings.length; i++) {
            Array.set(dest, i, mappings[i]);
        }
        return dest;
    }

    /**
     * PLAY-state {@code resource_pack_push} packet IDs across protocol
     * versions. Preserved from the previous Protocolize-based implementation
     * (which sourced them from Phoenix616's {@code packetmap.yml}).
     *
     * <p>Each {@link #addRange} call emits ONE entry marking the start of a
     * packet-ID range. Bungee's {@code ProtocolMapping} machinery infers the
     * end of each range from the start of the next one — registering one
     * mapping per protocol version triggers
     * {@code "Duplicate packet mapping (X, Y)"} at registration time. The
     * {@code endVer} parameter is preserved for readability only.</p>
     */
    private static List<int[]> playEntries() {
        List<int[]> list = new ArrayList<>();
        addRange(list, MC_1_8, MC_1_8, 0x48);
        addRange(list, MC_1_9, MC_1_11_2, 0x32);
        addRange(list, MC_1_12, MC_1_12, 0x33);
        addRange(list, MC_1_12_1, MC_1_12_2, 0x34);
        addRange(list, MC_1_13, MC_1_13_2, 0x37);
        addRange(list, MC_1_14, MC_1_14_4, 0x39);
        addRange(list, MC_1_15, MC_1_15_2, 0x3A);
        addRange(list, MC_1_16, MC_1_16_1, 0x39);
        addRange(list, MC_1_16_2, MC_1_16_5, 0x38);
        addRange(list, MC_1_17, MC_1_18_2, 0x3C);
        addRange(list, MC_1_19, MC_1_19, 0x3A);
        addRange(list, MC_1_19_1, MC_1_19_2, 0x3D);
        addRange(list, MC_1_19_3, MC_1_19_3, 0x3C);
        addRange(list, MC_1_19_4, MC_1_20_1, 0x40);
        addRange(list, MC_1_20_2, MC_1_20_2, 0x42);
        addRange(list, MC_1_20_3, MC_1_20_4, 0x44);
        addRange(list, MC_1_20_5, MC_1_21_1, 0x46);
        addRange(list, MC_1_21_2, MC_1_21_3, 0x4B);
        addRange(list, MC_1_21_4, MC_LATEST, 0x4A);
        return list;
    }

    /**
     * CONFIGURATION-state {@code resource_pack_push} packet IDs. The
     * CONFIGURATION state was added in 1.20.2; earlier versions don't have it.
     */
    private static List<int[]> configEntries() {
        List<int[]> list = new ArrayList<>();
        addRange(list, MC_1_20_2, MC_1_20_2, 0x06);
        addRange(list, MC_1_20_3, MC_1_20_4, 0x07);
        addRange(list, MC_1_20_5, MC_LATEST, 0x09);
        return list;
    }

    @SuppressWarnings("unused") // endVer kept for caller-site readability
    private static void addRange(List<int[]> sink, int startVer, int endVer, int packetId) {
        // Bungee's ProtocolMapping list infers the end of a packet-ID range
        // from the next mapping's startVer. Registering one mapping per
        // protocol version causes Protocol$DirectionData.registerPacket to
        // throw "Duplicate packet mapping (X, Y)". Emit only the range start.
        sink.add(new int[]{startVer, packetId});
    }

    public void onMergedPackReady(MergedPack pack) {
        this.current = pack;
    }

    @EventHandler
    public void onPostLogin(PostLoginEvent event) {
        MergedPack pack = current;
        if (pack == null) return;
        try {
            UUID packId = pack.packUuid() != null
                    ? pack.packUuid()
                    : UUID.nameUUIDFromBytes(pack.url().getBytes());
            ResourcePackPushPacket packet = new ResourcePackPushPacket(
                    packId,
                    pack.url(),
                    pack.sha1Hex() == null ? "" : pack.sha1Hex().toLowerCase(),
                    forceResourcePack);
            event.getPlayer().unsafe().sendPacket(packet);
        } catch (Throwable t) {
            logger.warn("[RSPM] Failed to send Java resource pack to "
                    + event.getPlayer().getName(), t);
        }
    }

    /**
     * Subclass of BungeeCord's {@link DefinedPacket} that serializes the
     * clientbound {@code resource_pack_push} packet.
     *
     * <p>Wire format (Minecraft 1.20.3+):</p>
     * <pre>
     *   UUID packId            (writeLong msb + writeLong lsb)
     *   String url             (DefinedPacket.writeString)
     *   String hash (hex, ≤40) (DefinedPacket.writeString)
     *   Boolean forced
     *   Boolean hasPromptMessage  (we always write false; no prompt)
     * </pre>
     *
     * <p>Read is unsupported — this packet is clientbound only. Bungee may
     * still invoke the no-arg constructor when populating its packet-class
     * registry, hence its visibility.</p>
     */
    public static final class ResourcePackPushPacket extends DefinedPacket {

        private UUID packId;
        private String url;
        private String hash;
        private boolean forced;

        public ResourcePackPushPacket() {
            // No-arg ctor required for Bungee's registry/supplier instantiation.
        }

        public ResourcePackPushPacket(UUID packId, String url, String hash, boolean forced) {
            this.packId = Objects.requireNonNull(packId, "packId");
            this.url = Objects.requireNonNull(url, "url");
            this.hash = hash == null ? "" : hash;
            this.forced = forced;
        }

        @Override
        public void read(ByteBuf buf, ProtocolConstants.Direction direction, int protocolVersion) {
            throw new UnsupportedOperationException("ResourcePackPushPacket is clientbound only; read is not implemented.");
        }

        @Override
        public void write(ByteBuf buf, ProtocolConstants.Direction direction, int protocolVersion) {
            buf.writeLong(packId.getMostSignificantBits());
            buf.writeLong(packId.getLeastSignificantBits());
            DefinedPacket.writeString(url, buf);
            DefinedPacket.writeString(hash, buf);
            buf.writeBoolean(forced);
            buf.writeBoolean(false); // hasPromptMessage
            // promptMessage skipped because hasPromptMessage = false.
        }

        @Override
        public void handle(AbstractPacketHandler handler) {
            // Clientbound; no handler invocation expected on the Bungee side.
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ResourcePackPushPacket)) return false;
            ResourcePackPushPacket other = (ResourcePackPushPacket) o;
            return forced == other.forced
                    && Objects.equals(packId, other.packId)
                    && Objects.equals(url, other.url)
                    && Objects.equals(hash, other.hash);
        }

        @Override
        public int hashCode() {
            return Objects.hash(packId, url, hash, forced);
        }

        @Override
        public String toString() {
            return "ResourcePackPushPacket{packId=" + packId
                    + ", url=" + url
                    + ", hash=" + hash
                    + ", forced=" + forced + "}";
        }
    }
}
