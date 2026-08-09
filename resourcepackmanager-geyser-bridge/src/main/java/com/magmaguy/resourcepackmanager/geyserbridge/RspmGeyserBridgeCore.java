package com.magmaguy.resourcepackmanager.geyserbridge;

import com.magmaguy.resourcepackmanager.bridge.BridgeChannels;
import com.magmaguy.resourcepackmanager.bridge.BridgeCodec;
import com.magmaguy.resourcepackmanager.bridge.BridgeEntityDefinition;
import com.magmaguy.resourcepackmanager.bridge.BridgeMessage;
import com.magmaguy.resourcepackmanager.bridge.BridgePropertyDefinition;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.entity.data.GeyserEntityDataTypes;
import org.geysermc.geyser.api.entity.property.GeyserEntityProperty;
import org.geysermc.geyser.api.entity.property.type.GeyserBooleanEntityProperty;
import org.geysermc.geyser.api.entity.property.type.GeyserFloatEntityProperty;
import org.geysermc.geyser.api.entity.property.type.GeyserIntEntityProperty;
import org.geysermc.geyser.api.entity.type.GeyserEntity;
import org.geysermc.geyser.api.event.java.ServerSpawnEntityEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntitiesEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntityPropertiesEvent;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.api.extension.ExtensionLogger;
import org.geysermc.geyser.api.util.Identifier;
import org.geysermc.geyser.entity.BedrockEntityDefinition;
import org.geysermc.geyser.entity.CustomBedrockEntityDefinition;
import org.geysermc.geyser.entity.properties.GeyserEntityProperties;
import org.geysermc.geyser.entity.type.Entity;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.PacketSendingEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundCustomPayloadPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundCustomPayloadPacket;

import java.lang.reflect.Method;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * All bridge logic that touches Geyser internals or 2.11+ API surfaces. This class
 * must only be loaded after {@link RspmGeyserBridgeExtension} has probed that this
 * Geyser build still ships the classes we compile against — never reference it from
 * a signature Geyser reflects over.
 * <p>
 * Geyser 2.11.0 introduced a public custom-entity API, which this bridge now uses
 * for the heavy lifting:
 * <ul>
 *   <li>{@link GeyserDefineEntitiesEvent#register} — registers custom Bedrock entity
 *   definitions.</li>
 *   <li>{@link GeyserDefineEntityPropertiesEvent} — registers every custom property
 *   during Geyser's dedicated property-definition window. Geyser intentionally
 *   rejects property creation after this event.</li>
 *   <li>{@link ServerSpawnEntityEvent#definition} — swaps the Bedrock definition of
 *   a spawning entity, replacing the vendored packet-translator copy the bridge
 *   shipped for pre-2.11 Geyser.</li>
 *   <li>{@link GeyserEntity#updateProperty} and {@link GeyserEntity#override} — apply
 *   runtime property/metadata updates.</li>
 * </ul>
 * Internals are still required for custom definition instances and for the downstream
 * packet listener that receives RSPM's plugin messages. Definitions that arrive after
 * Geyser freezes its registries are retained for the next restart; they are never
 * forced into the live registries.
 */
public class RspmGeyserBridgeCore {
    public static final int MAX_PROPERTY_VALUE = 1_000_000;
    public static final int MIN_PROPERTY_VALUE = -1_000_000;

    private static final String REGISTER_CHANNEL = "minecraft:register";
    // The minecraft:register payload separates channel names with NUL characters.
    private static final String REGISTER_CHANNEL_SEPARATOR = String.valueOf((char) 0);
    private static final String CUSTOM_ENTITY_CHANNEL = BridgeChannels.CUSTOM_ENTITY;
    private static final List<Path> BEDROCK_PACK_PATHS = List.of(
            // Velocity's default data directory is lowercase.
            Path.of("plugins", "resourcepackmanager", "work", "merged", "Bedrock.zip"),
            // Bungee's default data directory preserves the plugin name.
            Path.of("plugins", "ResourcePackManager", "work", "merged", "Bedrock.zip"),
            // Bukkit conversion output.
            Path.of("plugins", "ResourcePackManager", "output", "ResourcePackManager_Bedrock.zip")
    );
    private static final Pattern BEDROCK_QUERY_PROPERTY =
            Pattern.compile("query\\.property\\(['\\\"]([^'\\\"]+)['\\\"]\\)");
    private static final AtomicInteger SCHEDULER_THREAD_ID = new AtomicInteger();
    private static final ConcurrentMap<String, BridgeEntityDefinition> DEFINITIONS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, CustomBedrockEntityDefinition> LOADED_DEFINITIONS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, ConcurrentMap<String, GeyserEntityProperty<?>>> REGISTERED_PROPERTIES =
            new ConcurrentHashMap<>();
    private static final ConcurrentMap<GeyserConnection, ConcurrentMap<Integer, String>> CUSTOM_ENTITIES = new ConcurrentHashMap<>();
    private static volatile boolean geyserLoaded;
    private static volatile boolean entityDefinitionWindowClosed;
    private static volatile boolean entityPropertyWindowClosed;
    private static volatile boolean warnedDeferredDefinition;
    private static volatile boolean warnedPayloadChannelReflectionFailure;
    private static volatile boolean warnedPackPreloadFailure;
    private static volatile boolean warnedRuntimePropertyUpdateFailure;
    private static volatile boolean warnedUnregisteredSpawnDefinition;
    private static volatile String preloadedPackSignature;
    private static volatile boolean loggedLateEntityReplacement;

    private final RspmGeyserBridgeExtension extension;
    private final ScheduledExecutorService scheduler;
    private volatile boolean shuttingDown;
    private volatile boolean versionedEventsSubscribed;

    public RspmGeyserBridgeCore(RspmGeyserBridgeExtension extension) {
        this.extension = extension;
        this.scheduler = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable,
                    "rspm-geyser-bridge-" + SCHEDULER_THREAD_ID.incrementAndGet());
            thread.setDaemon(true);
            thread.setContextClassLoader(ClassLoader.getPlatformClassLoader());
            return thread;
        });
    }

    private ExtensionLogger logger() {
        return extension.logger();
    }

    /**
     * Subscribes to API events that only exist on Geyser 2.11+. Done here (not with
     * {@code @Subscribe} methods on the extension class) so older Geyser builds never
     * resolve these event classes; the callbacks route through the extension's
     * linkage guard like every other core entry point.
     */
    public synchronized void subscribeVersionedEvents() {
        if (versionedEventsSubscribed) {
            return;
        }
        extension.eventBus().subscribe(GeyserDefineEntitiesEvent.class,
                event -> extension.guarded(() -> onDefineEntities(event)));
        extension.eventBus().subscribe(GeyserDefineEntityPropertiesEvent.class,
                event -> extension.guarded(() -> onDefineEntityProperties(event)));
        extension.eventBus().subscribe(ServerSpawnEntityEvent.class,
                event -> extension.guarded(() -> onServerSpawnEntity(event)));
        versionedEventsSubscribed = true;
    }

    public void onDefineEntities(GeyserDefineEntitiesEvent event) {
        try {
            preloadDefinitionsFromBedrockPack();
            DEFINITIONS.values().forEach(definition -> {
                CustomBedrockEntityDefinition entityDefinition = buildDefinition(definition);
                event.register(entityDefinition);
                LOADED_DEFINITIONS.put(definition.identifier(), entityDefinition);
            });
            if (!LOADED_DEFINITIONS.isEmpty()) {
                logger().info("Registered " + LOADED_DEFINITIONS.size()
                        + " RSPM custom Bedrock entity definitions with Geyser.");
            }
        } finally {
            entityDefinitionWindowClosed = true;
        }
    }

    public void onDefineEntityProperties(GeyserDefineEntityPropertiesEvent event) {
        try {
            DEFINITIONS.values().forEach(definition -> registerProperties(event, definition));
        } finally {
            entityPropertyWindowClosed = true;
        }
    }

    public synchronized void onPostInitialize() {
        if (geyserLoaded) {
            return;
        }
        geyserLoaded = true;
        boolean definitionWindowsObserved = entityDefinitionWindowClosed && entityPropertyWindowClosed;
        if (!definitionWindowsObserved) {
            logger().severe("RSPM GEYSER BRIDGE UNHEALTHY: the extension loaded, but Geyser's custom entity "
                    + "definition/property registration window was missed. Bedrock can receive the resource pack "
                    + "while custom models still render as their vanilla carrier entities. Fully restart the "
                    + "proxy/server with this ResourcePackManager artifact; do not use /reload.");
        }
        if (LOADED_DEFINITIONS.isEmpty()) {
            scheduleBedrockPackPreload(0);
        }
        extension.logStartupHealth(
                LOADED_DEFINITIONS.size(), currentBedrockPackUuid(), definitionWindowsObserved);
    }

    public void onServerSpawnEntity(ServerSpawnEntityEvent event) {
        Map<Integer, String> sessionEntities = CUSTOM_ENTITIES.get(event.connection());
        if (sessionEntities == null) {
            return;
        }
        String identifier = sessionEntities.get(event.entityId());
        if (identifier == null) {
            return;
        }
        CustomBedrockEntityDefinition definition = LOADED_DEFINITIONS.get(identifier);
        if (definition == null) {
            return;
        }
        if (!definition.registered()) {
            warnUnregisteredDefinition(identifier);
            return;
        }
        event.definition(definition);
    }

    public void onSessionJoin(GeyserConnection connection) {
        // Velocity may deliver this through both Geyser's automatic extension
        // registration and RSPM's programmatic lifecycle relay. Only the first
        // callback may attach a downstream packet listener.
        if (CUSTOM_ENTITIES.putIfAbsent(connection, new ConcurrentHashMap<>()) != null) {
            return;
        }
        if (connection instanceof GeyserSession session) {
            registerPacketListener(session, 0);
        }
    }

    public void onSessionQuit(GeyserConnection connection) {
        CUSTOM_ENTITIES.remove(connection);
    }

    private void warnUnregisteredDefinition(String identifier) {
        if (!warnedUnregisteredSpawnDefinition) {
            warnedUnregisteredSpawnDefinition = true;
            logger().warning("RSPM custom Bedrock entity definition " + identifier + " is not registered with "
                    + "Geyser. It likely arrived after Geyser initialized; restart the server after pack "
                    + "generation so definitions are available during Geyser's entity registration.");
        }
    }

    private CustomBedrockEntityDefinition buildDefinition(BridgeEntityDefinition definition) {
        CustomBedrockEntityDefinition existing = LOADED_DEFINITIONS.get(definition.identifier());
        if (existing != null) {
            return existing;
        }
        return new CustomBedrockEntityDefinition(
                Identifier.of(definition.identifier()), new GeyserEntityProperties());
    }

    private void registerProperties(
            GeyserDefineEntityPropertiesEvent event, BridgeEntityDefinition definition) {
        if (definition == null || !LOADED_DEFINITIONS.containsKey(definition.identifier())) {
            return;
        }

        Identifier entityIdentifier = Identifier.of(definition.identifier());
        ConcurrentMap<String, GeyserEntityProperty<?>> registered =
                REGISTERED_PROPERTIES.computeIfAbsent(
                        definition.identifier(), ignored -> new ConcurrentHashMap<>());
        for (BridgePropertyDefinition property : definition.properties()) {
            if (property == null || property.identifier() == null || property.type() == null
                    || registered.containsKey(property.identifier())) {
                continue;
            }

            Identifier propertyIdentifier = Identifier.of(property.identifier());
            GeyserEntityProperty<?> handle = switch (property.type().toUpperCase(Locale.ROOT)) {
                case "BOOLEAN", "BOOL" ->
                        event.registerBooleanProperty(entityIdentifier, propertyIdentifier, false);
                case "FLOAT", "DOUBLE" ->
                        event.registerFloatProperty(
                                entityIdentifier, propertyIdentifier,
                                MIN_PROPERTY_VALUE, MAX_PROPERTY_VALUE, 0f);
                case "INTEGER", "INT" ->
                        event.registerIntegerProperty(
                                entityIdentifier, propertyIdentifier,
                                MIN_PROPERTY_VALUE, MAX_PROPERTY_VALUE, 0);
                default -> {
                    logger().warning("Unknown RSPM custom entity property type " + property.type()
                            + " for " + property.identifier());
                    yield null;
                }
            };
            if (handle != null) {
                registered.put(property.identifier(), handle);
            }
        }
    }

    private void scheduleBedrockPackPreload(int attempt) {
        schedule(() -> extension.guarded(() -> {
            int loaded = preloadDefinitionsFromBedrockPack();
            if (loaded > 0) {
                warnDeferredDefinitions();
            }
            if (loaded == 0 && attempt < 180) {
                scheduleBedrockPackPreload(attempt + 1);
            }
        }), attempt == 0 ? 250 : 1000);
    }

    private void warnDeferredDefinitions() {
        if (!entityDefinitionWindowClosed || warnedDeferredDefinition) {
            return;
        }
        warnedDeferredDefinition = true;
        logger().warning("RSPM custom Bedrock entity definitions became available after Geyser closed its "
                + "startup registration windows. They were saved for the next proxy restart instead of being "
                + "forced into Geyser's frozen registries. Bedrock players will stay connected; restart the "
                + "proxy once to activate the newly generated custom models.");
    }

    private int preloadDefinitionsFromBedrockPack() {
        Path packPath = findBedrockPackPath();
        if (packPath == null) {
            return 0;
        }
        try {
            String signature = packPath.toAbsolutePath() + ":" + Files.size(packPath)
                    + ":" + Files.getLastModifiedTime(packPath).toMillis();
            if (signature.equals(preloadedPackSignature)) {
                return DEFINITIONS.size();
            }

            int loaded = 0;
            try (ZipFile zipFile = new ZipFile(packPath.toFile())) {
                Enumeration<? extends ZipEntry> entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()
                            || !entry.getName().startsWith("entity/")
                            || !entry.getName().endsWith(".entity.json")) {
                        continue;
                    }

                    String identifier = readClientEntityIdentifier(zipFile, entry);
                    if (identifier == null || identifier.isBlank()) {
                        continue;
                    }

                    BridgeEntityDefinition definition = new BridgeEntityDefinition(
                            identifier, 1.0f, 2.0f, readPropertyDefinitions(zipFile, entry));
                    BridgeEntityDefinition merged = mergeDefinition(DEFINITIONS.get(identifier), definition);
                    DEFINITIONS.put(identifier, merged);
                    loaded++;
                }
            }

            preloadedPackSignature = signature;
            if (loaded > 0) {
                long propertyDefinitions = DEFINITIONS.values().stream()
                        .mapToLong(definition -> definition.properties().size())
                        .sum();
                logger().info("Preloaded " + loaded + " custom Bedrock entity identifiers and "
                        + propertyDefinitions + " property definition(s) from " + packPath + ".");
            }
            return loaded;
        } catch (Exception exception) {
            if (!warnedPackPreloadFailure) {
                warnedPackPreloadFailure = true;
                logger().warning("Could not preload custom Bedrock entity identifiers from "
                        + packPath + ": " + exception.getMessage());
            }
            return 0;
        }
    }

    private Path findBedrockPackPath() {
        Path root = Path.of(System.getProperty("user.dir"));
        for (Path relativePath : BEDROCK_PACK_PATHS) {
            Path candidate = root.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private String currentBedrockPackUuid() {
        Path packPath = findBedrockPackPath();
        if (packPath == null) {
            return "none";
        }
        try (ZipFile zipFile = new ZipFile(packPath.toFile())) {
            ZipEntry manifestEntry = zipFile.getEntry("manifest.json");
            if (manifestEntry == null || manifestEntry.isDirectory()) {
                return "missing-manifest";
            }
            JsonObject root = JsonParser.parseString(readZipEntry(zipFile, manifestEntry)).getAsJsonObject();
            JsonObject header = root.has("header") ? root.getAsJsonObject("header") : null;
            if (header == null || !header.has("uuid")) {
                return "missing-uuid";
            }
            return UUID.fromString(header.get("uuid").getAsString()).toString();
        } catch (Exception exception) {
            return "invalid";
        }
    }

    private List<BridgePropertyDefinition> readPropertyDefinitions(ZipFile zipFile, ZipEntry entityEntry) throws Exception {
        String modelId = modelIdFromEntityEntry(entityEntry.getName());
        if (modelId == null) {
            return List.of();
        }

        Map<String, BridgePropertyDefinition> properties = new LinkedHashMap<>();
        collectIntegerPropertyReferences(zipFile, "animation_controllers/" + modelId + ".animation_controllers.json", properties);
        collectIntegerPropertyReferences(zipFile, "render_controllers/" + modelId + ".render_controllers.json", properties);
        return List.copyOf(properties.values());
    }

    private String modelIdFromEntityEntry(String entryName) {
        String prefix = "entity/";
        String suffix = ".entity.json";
        if (entryName == null || !entryName.startsWith(prefix) || !entryName.endsWith(suffix)) {
            return null;
        }
        return entryName.substring(prefix.length(), entryName.length() - suffix.length());
    }

    private void collectIntegerPropertyReferences(ZipFile zipFile, String entryName,
                                                  Map<String, BridgePropertyDefinition> properties) throws Exception {
        ZipEntry entry = zipFile.getEntry(entryName);
        if (entry == null || entry.isDirectory()) {
            return;
        }

        Matcher matcher = BEDROCK_QUERY_PROPERTY.matcher(readZipEntry(zipFile, entry));
        while (matcher.find()) {
            String identifier = matcher.group(1);
            if (identifier != null && !identifier.isBlank()) {
                properties.putIfAbsent(identifier, new BridgePropertyDefinition(identifier, "INT"));
            }
        }
    }

    private String readZipEntry(ZipFile zipFile, ZipEntry entry) throws Exception {
        try (Reader reader = new InputStreamReader(zipFile.getInputStream(entry), StandardCharsets.UTF_8)) {
            StringBuilder builder = new StringBuilder();
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                builder.append(buffer, 0, read);
            }
            return builder.toString();
        }
    }

    private String readClientEntityIdentifier(ZipFile zipFile, ZipEntry entry) throws Exception {
        try (Reader reader = new InputStreamReader(zipFile.getInputStream(entry), StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject clientEntity = root.has("minecraft:client_entity")
                    ? root.getAsJsonObject("minecraft:client_entity") : null;
            if (clientEntity == null || !clientEntity.has("description")) {
                return null;
            }
            JsonObject description = clientEntity.getAsJsonObject("description");
            return description.has("identifier") ? description.get("identifier").getAsString() : null;
        }
    }

    private void registerPacketListener(GeyserSession session, int attempt) {
        schedule(() -> extension.guarded(() -> {
            if (session.getDownstream() == null || session.getDownstream().getSession() == null) {
                if (attempt < 80) {
                    registerPacketListener(session, attempt + 1);
                } else {
                    logger().warning("Could not attach RSPM custom entity bridge listener for " + session.javaUsername());
                }
                return;
            }

            session.getDownstream().getSession().addListener(new SessionAdapter() {
                @Override
                public void packetSending(PacketSendingEvent event) {
                    Packet packet = event.getPacket();
                    if (packet instanceof ServerboundCustomPayloadPacket payloadPacket
                            && payloadChannelEquals(payloadPacket, REGISTER_CHANNEL)) {
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
                    if (packet instanceof ClientboundCustomPayloadPacket payloadPacket
                            && payloadChannelEquals(payloadPacket, CUSTOM_ENTITY_CHANNEL)) {
                        extension.guarded(() -> handleMessage(session, BridgeCodec.decode(payloadPacket.getData())));
                    }
                }
            });
        }), attempt == 0 ? 0 : 25);
    }

    private void schedule(Runnable action, long delayMillis) {
        if (shuttingDown) {
            return;
        }
        try {
            scheduler.schedule(action, delayMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException exception) {
            if (!shuttingDown) {
                throw exception;
            }
        }
    }

    public void shutdown() {
        shuttingDown = true;
        scheduler.shutdownNow();
        CUSTOM_ENTITIES.clear();
        DEFINITIONS.clear();
        LOADED_DEFINITIONS.clear();
        REGISTERED_PROPERTIES.clear();
        geyserLoaded = false;
        entityDefinitionWindowClosed = false;
        entityPropertyWindowClosed = false;
        warnedDeferredDefinition = false;
        preloadedPackSignature = null;
        versionedEventsSubscribed = false;
    }

    private boolean payloadChannelEquals(Object payloadPacket, String expectedChannel) {
        String actualChannel = payloadChannel(payloadPacket);
        return expectedChannel.equals(actualChannel);
    }

    private String payloadChannel(Object payloadPacket) {
        try {
            Object channel = payloadPacket.getClass().getMethod("getChannel").invoke(payloadPacket);
            return channelToString(channel);
        } catch (ReflectiveOperationException | LinkageError e) {
            if (!warnedPayloadChannelReflectionFailure) {
                warnedPayloadChannelReflectionFailure = true;
                logger().warning("Could not read Geyser custom-payload channel reflectively. "
                        + "RSPM custom Bedrock entity bridge messages will be ignored: " + e.getMessage());
            }
            return null;
        }
    }

    private String channelToString(Object channel) throws ReflectiveOperationException {
        if (channel == null) {
            return null;
        }
        if (channel instanceof CharSequence charSequence) {
            return charSequence.toString();
        }

        Object asString = invokeNoArgIfPresent(channel, "asString");
        if (asString instanceof String value) {
            return value;
        }

        Object namespace = invokeNoArgIfPresent(channel, "namespace");
        Object value = invokeNoArgIfPresent(channel, "value");
        if (namespace instanceof String namespaceString && value instanceof String valueString) {
            return namespaceString + ":" + valueString;
        }

        return channel.toString();
    }

    private Object invokeNoArgIfPresent(Object target, String methodName) throws ReflectiveOperationException {
        Method method = findNoArgMethod(target.getClass(), methodName);
        if (method == null) {
            return null;
        }
        method.setAccessible(true);
        return method.invoke(target);
    }

    private Method findNoArgMethod(Class<?> type, String methodName) {
        if (type == null) {
            return null;
        }

        for (Class<?> iface : type.getInterfaces()) {
            Method method = findNoArgMethod(iface, methodName);
            if (method != null) {
                return method;
            }
        }

        try {
            return type.getDeclaredMethod(methodName);
        } catch (NoSuchMethodException ignored) {
            return findNoArgMethod(type.getSuperclass(), methodName);
        }
    }

    private void handleMessage(GeyserSession session, BridgeMessage message) {
        if (message == null || message.type() == null) {
            return;
        }

        switch (message.type()) {
            case REGISTER_DEFINITION -> registerDefinition(message.definition());
            case SET_CUSTOM_ENTITY -> markCustomEntity(session, message.entityId(), message.identifier());
            case ENTITY_DATA -> applyEntityData(session, message);
            case PROPERTIES -> applyProperties(session, message);
        }
    }

    private void registerDefinition(BridgeEntityDefinition definition) {
        if (definition == null || definition.identifier() == null || definition.identifier().isBlank()) {
            return;
        }

        BridgeEntityDefinition merged = mergeDefinition(DEFINITIONS.get(definition.identifier()), definition);
        DEFINITIONS.put(definition.identifier(), merged);
        if (entityDefinitionWindowClosed || entityPropertyWindowClosed || geyserLoaded) {
            warnDeferredDefinitions();
        }
    }

    private BridgeEntityDefinition mergeDefinition(BridgeEntityDefinition existing, BridgeEntityDefinition incoming) {
        if (existing == null) {
            return incoming;
        }
        if (incoming == null) {
            return existing;
        }

        Map<String, BridgePropertyDefinition> properties = new LinkedHashMap<>();
        existing.properties().forEach(property -> properties.put(property.identifier(), property));
        incoming.properties().forEach(property -> properties.put(property.identifier(), property));
        float width = incoming.width() > 0 ? incoming.width() : existing.width();
        float height = incoming.height() > 0 ? incoming.height() : existing.height();
        return new BridgeEntityDefinition(incoming.identifier(), width, height, List.copyOf(properties.values()));
    }

    private void markCustomEntity(GeyserSession session, int entityId, String identifier) {
        if (identifier == null || !DEFINITIONS.containsKey(identifier)) {
            return;
        }

        CUSTOM_ENTITIES.computeIfAbsent(session, ignored -> new ConcurrentHashMap<>()).put(entityId, identifier);
        replaceAlreadySpawnedEntity(session, entityId, identifier);
    }

    /**
     * If the SET_CUSTOM_ENTITY message arrives after the entity already spawned (the
     * usual case: the Java plugin only learns the entity id after spawning it), swap
     * the live entity's Bedrock definition and respawn it for the Bedrock client.
     * Spawns that happen after the mark is in place are handled by
     * {@link #onServerSpawnEntity}.
     */
    private void replaceAlreadySpawnedEntity(GeyserSession session, int entityId, String identifier) {
        CustomBedrockEntityDefinition customDefinition = LOADED_DEFINITIONS.get(identifier);
        if (customDefinition == null || !customDefinition.registered()) {
            if (customDefinition != null) {
                warnUnregisteredDefinition(identifier);
            }
            return;
        }

        Entity existing = session.getEntityCache().getEntityByJavaId(entityId);
        if (existing == null || !existing.isValid()) {
            return;
        }
        BedrockEntityDefinition existingDefinition = existing.bedrockDefinition();
        if (existingDefinition != null && customDefinition.identifier().equals(existingDefinition.identifier())) {
            return;
        }

        existing.despawnEntity();
        existing.bedrockDefinition(customDefinition);
        existing.spawnEntity();

        if (!loggedLateEntityReplacement) {
            loggedLateEntityReplacement = true;
            logger().info("RSPM custom Bedrock entity bridge replaced an already-spawned carrier entity "
                    + entityId + " with " + identifier + ".");
        }
    }

    private void applyEntityData(GeyserSession session, BridgeMessage message) {
        Entity entity = session.getEntityCache().getEntityByJavaId(message.entityId());
        if (entity == null) {
            return;
        }

        GeyserEntity apiEntity = entity;
        if (message.height() != null) {
            apiEntity.override(GeyserEntityDataTypes.HEIGHT, message.height());
        }
        if (message.width() != null) {
            apiEntity.override(GeyserEntityDataTypes.WIDTH, message.width());
        }
        if (message.scale() != null) {
            apiEntity.override(GeyserEntityDataTypes.SCALE, message.scale());
        }
        if (message.color() != null) {
            apiEntity.override(GeyserEntityDataTypes.COLOR, (byte) closestDyeColor(message.color()));
        }
        if (message.variant() != null) {
            apiEntity.override(GeyserEntityDataTypes.VARIANT, message.variant());
        }
        entity.updateBedrockMetadata();
    }

    private void applyProperties(GeyserSession session, BridgeMessage message) {
        Entity entity = session.getEntityCache().getEntityByJavaId(message.entityId());
        if (entity == null) {
            return;
        }

        String definitionId = customIdentifierOf(session, message.entityId());
        CustomBedrockEntityDefinition definition = definitionId == null ? null : LOADED_DEFINITIONS.get(definitionId);
        if (definition == null) {
            return;
        }

        for (Map.Entry<String, Object> entry : message.properties().entrySet()) {
            GeyserEntityProperty<?> property = findProperty(definitionId, entry.getKey());
            if (property == null) {
                continue;
            }
            updateRuntimeProperty(entity, property, entry.getValue());
        }
    }

    private String customIdentifierOf(GeyserSession session, int entityId) {
        Map<Integer, String> sessionEntities = CUSTOM_ENTITIES.get(session);
        return sessionEntities == null ? null : sessionEntities.get(entityId);
    }

    private GeyserEntityProperty<?> findProperty(String definitionIdentifier, String propertyIdentifier) {
        Map<String, GeyserEntityProperty<?>> properties =
                REGISTERED_PROPERTIES.get(definitionIdentifier);
        return properties == null ? null : properties.get(propertyIdentifier);
    }

    @SuppressWarnings("unchecked")
    private void updateRuntimeProperty(GeyserEntity entity, GeyserEntityProperty<?> property, Object value) {
        Object coerced = coercePropertyValue(property, value);
        if (coerced == null) {
            return;
        }

        try {
            entity.updateProperty((GeyserEntityProperty<Object>) property, coerced);
        } catch (RuntimeException exception) {
            if (!warnedRuntimePropertyUpdateFailure) {
                warnedRuntimePropertyUpdateFailure = true;
                logger().warning("Could not apply RSPM custom Bedrock entity property update for "
                        + property.identifier() + ": " + exception.getMessage());
            }
        }
    }

    private Object coercePropertyValue(GeyserEntityProperty<?> property, Object value) {
        if (value == null) {
            return null;
        }
        if (property instanceof GeyserBooleanEntityProperty) {
            return value instanceof Boolean booleanValue ? booleanValue : null;
        }
        if (property instanceof GeyserFloatEntityProperty) {
            return value instanceof Number number ? number.floatValue() : null;
        }
        if (property instanceof GeyserIntEntityProperty) {
            return value instanceof Number number ? number.intValue() : null;
        }
        return null;
    }

    private int closestDyeColor(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int[][] colors = {
                {249, 255, 254},
                {249, 128, 29},
                {199, 78, 189},
                {58, 179, 218},
                {254, 216, 61},
                {128, 199, 31},
                {243, 139, 170},
                {71, 79, 82},
                {159, 157, 151},
                {22, 156, 156},
                {137, 50, 184},
                {60, 68, 170},
                {131, 84, 50},
                {94, 124, 22},
                {176, 46, 38},
                {29, 29, 33}
        };

        int closest = 0;
        long bestDistance = Long.MAX_VALUE;
        for (int i = 0; i < colors.length; i++) {
            long dr = r - colors[i][0];
            long dg = g - colors[i][1];
            long db = b - colors[i][2];
            long distance = dr * dr + dg * dg + db * db;
            if (distance < bestDistance) {
                closest = i;
                bestDistance = distance;
            }
        }
        return closest;
    }
}
