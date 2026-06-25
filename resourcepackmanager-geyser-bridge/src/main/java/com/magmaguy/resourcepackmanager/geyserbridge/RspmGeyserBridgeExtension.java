package com.magmaguy.resourcepackmanager.geyserbridge;

import com.magmaguy.resourcepackmanager.bridge.BridgeChannels;
import com.magmaguy.resourcepackmanager.bridge.BridgeCodec;
import com.magmaguy.resourcepackmanager.bridge.BridgeEntityDefinition;
import com.magmaguy.resourcepackmanager.bridge.BridgeMessage;
import com.magmaguy.resourcepackmanager.bridge.BridgePropertyDefinition;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.event.bedrock.SessionDisconnectEvent;
import org.geysermc.geyser.api.event.bedrock.SessionLoginEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntityPropertiesEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.api.util.Identifier;
import org.geysermc.geyser.entity.EntityDefinition;
import org.geysermc.geyser.entity.spawn.EntitySpawnContext;
import org.geysermc.geyser.entity.properties.GeyserEntityProperties;
import org.geysermc.geyser.entity.properties.type.BooleanProperty;
import org.geysermc.geyser.entity.properties.type.FloatProperty;
import org.geysermc.geyser.entity.properties.type.IntProperty;
import org.geysermc.geyser.entity.type.Entity;
import org.geysermc.geyser.registry.Registries;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.PacketSendingEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundCustomPayloadPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundCustomPayloadPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket;

import java.lang.reflect.Method;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class RspmGeyserBridgeExtension implements Extension {
    public static final int MAX_PROPERTY_VALUE = 1_000_000;
    public static final int MIN_PROPERTY_VALUE = -1_000_000;

    private static final String REGISTER_CHANNEL = "minecraft:register";
    private static final String CUSTOM_ENTITY_CHANNEL = BridgeChannels.CUSTOM_ENTITY;
    private static final List<Path> BEDROCK_PACK_PATHS = List.of(
            Path.of("plugins", "ResourcePackManager", "work", "merged", "Bedrock.zip"),
            Path.of("plugins", "ResourcePackManager", "output", "ResourcePackManager_Bedrock.zip")
    );
    private static final Pattern BEDROCK_QUERY_PROPERTY =
            Pattern.compile("query\\.property\\(['\\\"]([^'\\\"]+)['\\\"]\\)");
    private static final ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(2);
    private static final ConcurrentMap<String, BridgeEntityDefinition> DEFINITIONS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, EntityDefinition<?>> LOADED_DEFINITIONS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<GeyserConnection, ConcurrentMap<Integer, String>> CUSTOM_ENTITIES = new ConcurrentHashMap<>();
    private static volatile boolean geyserLoaded;
    private static volatile RspmGeyserBridgeExtension instance;
    private static volatile boolean warnedLateRegistryChange;
    private static volatile boolean warnedLatePropertiesUnavailable;
    private static volatile boolean warnedPayloadChannelReflectionFailure;
    private static volatile boolean warnedPackPreloadFailure;
    private static volatile boolean warnedPropertyRegistryInstallFailure;
    private static volatile boolean warnedRuntimePropertyUpdateFailure;
    private static volatile String preloadedPackSignature;
    private static volatile boolean propertyDefinitionEventActive;
    private static volatile boolean loggedLateEntityReplacement;

    public RspmGeyserBridgeExtension() {
        instance = this;
    }

    public static String customIdentifier(GeyserSession session, int entityId) {
        Map<Integer, String> sessionEntities = CUSTOM_ENTITIES.get(session);
        return sessionEntities == null ? null : sessionEntities.get(entityId);
    }

    public static EntityDefinition<?> loadedDefinition(String identifier, EntityDefinition<?> fallback) {
        return LOADED_DEFINITIONS.getOrDefault(identifier, fallback);
    }

    @Subscribe
    public void onPostInitialize(GeyserPostInitializeEvent event) {
        replaceTranslator();
        geyserLoaded = true;
        int preloaded = preloadDefinitionsFromBedrockPack();
        DEFINITIONS.values().forEach(definition -> registerEntityToGeyser(definition, false));
        if (preloaded == 0) {
            scheduleBedrockPackPreload(0);
        }
        logger().info("ResourcePackManager Geyser bridge ready with " + LOADED_DEFINITIONS.size() + " custom entity definitions.");
    }

    @Subscribe
    public void onDefineProperties(GeyserDefineEntityPropertiesEvent event) {
        propertyDefinitionEventActive = true;
        try {
            preloadDefinitionsFromBedrockPack();
            List<EntityDefinition<?>> propertyDefinitions = new ArrayList<>();
            DEFINITIONS.values().forEach(definition -> {
                EntityDefinition<?> entityDefinition = registerEntityToGeyser(definition, false);
                if (hasRegisteredProperties(entityDefinition)) {
                    propertyDefinitions.add(entityDefinition);
                }
            });
            installPropertyRegistryEntries(propertyDefinitions);
        } finally {
            propertyDefinitionEventActive = false;
        }
    }

    @Subscribe
    public void onSessionJoin(SessionLoginEvent event) {
        CUSTOM_ENTITIES.put(event.connection(), new ConcurrentHashMap<>());
        if (event.connection() instanceof GeyserSession session) {
            registerPacketListener(session, 0);
        }
    }

    @Subscribe
    public void onSessionQuit(SessionDisconnectEvent event) {
        CUSTOM_ENTITIES.remove(event.connection());
    }

    private void replaceTranslator() {
        Registries.JAVA_PACKET_TRANSLATORS.register(ClientboundAddEntityPacket.class, new RspmJavaAddEntityTranslator());
    }

    private void scheduleBedrockPackPreload(int attempt) {
        SCHEDULER.schedule(() -> {
            int loaded = preloadDefinitionsFromBedrockPack();
            if (loaded > 0) {
                DEFINITIONS.values().forEach(definition -> registerEntityToGeyser(definition, false));
            }
            if (loaded == 0 && attempt < 180) {
                scheduleBedrockPackPreload(attempt + 1);
            }
        }, attempt == 0 ? 250 : 1000, TimeUnit.MILLISECONDS);
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
        SCHEDULER.schedule(() -> {
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
                        if (Arrays.asList(channels.split("\u0000")).contains(BridgeChannels.CUSTOM_ENTITY)) {
                            return;
                        }
                        event.setPacket(payloadPacket.withData(
                                (channels + "\u0000" + BridgeChannels.CUSTOM_ENTITY).getBytes(StandardCharsets.UTF_8)));
                    }
                }

                @Override
                public void packetReceived(Session tcpSession, Packet packet) {
                    if (packet instanceof ClientboundCustomPayloadPacket payloadPacket
                            && payloadChannelEquals(payloadPacket, CUSTOM_ENTITY_CHANNEL)) {
                        handleMessage(session, BridgeCodec.decode(payloadPacket.getData()));
                    }
                }
            });
        }, attempt == 0 ? 0 : 25, TimeUnit.MILLISECONDS);
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
        boolean newDefinition = DEFINITIONS.put(definition.identifier(), merged) == null;
        if (geyserLoaded) {
            registerEntityToGeyser(merged, newDefinition);
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

        EntityDefinition<?> customDefinition = registerEntityToGeyser(DEFINITIONS.get(identifier), false);
        CUSTOM_ENTITIES.computeIfAbsent(session, ignored -> new ConcurrentHashMap<>()).put(entityId, identifier);
        replaceAlreadySpawnedEntity(session, entityId, identifier, customDefinition);
    }

    private void replaceAlreadySpawnedEntity(GeyserSession session, int entityId, String identifier,
                                             EntityDefinition<?> customDefinition) {
        if (customDefinition == null) {
            return;
        }

        Entity existing = session.getEntityCache().getEntityByJavaId(entityId);
        if (existing == null || !existing.isValid()) {
            return;
        }
        EntityDefinition<?> existingDefinition = existing.getDefinition();
        if (existingDefinition != null && identifier.equals(existingDefinition.identifier())) {
            return;
        }

        Entity replacement = createEntity(customDefinition, new EntitySpawnContext(
                session,
                customDefinition,
                existing.getEntityId(),
                existing.uuid(),
                existing.getPosition(),
                existing.getMotion(),
                existing.getYaw(),
                existing.getPitch(),
                existing.getHeadYaw(),
                null));
        replacement.setPosition(existing.getPosition());
        replacement.setMotion(existing.getMotion());
        replacement.setYaw(existing.getYaw());
        replacement.setPitch(existing.getPitch());
        replacement.setHeadYaw(existing.getHeadYaw());
        replacement.setOnGround(existing.isOnGround());

        session.getEntityCache().removeEntity(existing);
        session.getEntityCache().spawnEntity(replacement);

        if (!loggedLateEntityReplacement) {
            loggedLateEntityReplacement = true;
            logger().info("RSPM custom Bedrock entity bridge replaced an already-spawned carrier entity "
                    + entityId + " with " + identifier + ".");
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Entity createEntity(EntityDefinition<?> definition, EntitySpawnContext context) {
        return ((EntityDefinition) definition).factory().create(context);
    }

    private void applyEntityData(GeyserSession session, BridgeMessage message) {
        Entity entity = session.getEntityCache().getEntityByJavaId(message.entityId());
        if (entity == null) {
            return;
        }

        if (message.height() != null) {
            entity.setBoundingBoxHeight(message.height());
        }
        if (message.width() != null) {
            entity.setBoundingBoxWidth(message.width());
        }
        if (message.scale() != null) {
            entity.getDirtyMetadata().put(EntityDataTypes.SCALE, message.scale());
        }
        if (message.color() != null) {
            entity.getDirtyMetadata().put(EntityDataTypes.COLOR, (byte) closestDyeColor(message.color()));
        }
        if (message.variant() != null) {
            entity.getDirtyMetadata().put(EntityDataTypes.VARIANT, message.variant());
        }
        entity.updateBedrockMetadata();
    }

    private void applyProperties(GeyserSession session, BridgeMessage message) {
        Entity entity = session.getEntityCache().getEntityByJavaId(message.entityId());
        if (entity == null || entity.getPropertyManager() == null) {
            return;
        }

        String definitionId = customIdentifier(session, message.entityId());
        BridgeEntityDefinition definition = definitionId == null ? null : DEFINITIONS.get(definitionId);
        for (Map.Entry<String, Object> entry : message.properties().entrySet()) {
            BridgePropertyDefinition propertyDefinition = findProperty(definition, entry.getKey());
            if (propertyDefinition == null) {
                continue;
            }
            addRuntimeProperty(entity, propertyDefinition, entry.getKey(), entry.getValue());
        }
        entity.updateBedrockEntityProperties();
    }

    private BridgePropertyDefinition findProperty(BridgeEntityDefinition definition, String identifier) {
        if (definition == null) {
            return null;
        }
        return definition.properties().stream()
                .filter(property -> property.identifier().equals(identifier))
                .findFirst()
                .orElse(null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void addRuntimeProperty(Entity entity, BridgePropertyDefinition definition, String identifier, Object value) {
        String type = definition == null ? inferPropertyType(value) : definition.type();
        if (type == null || identifier == null || value == null) {
            return;
        }

        try {
            switch (type.toUpperCase(Locale.ROOT)) {
                case "BOOLEAN", "BOOL" -> {
                    if (value instanceof Boolean booleanValue) {
                        entity.getPropertyManager().addProperty(new BooleanProperty(Identifier.of(identifier), false), booleanValue);
                    }
                }
                case "FLOAT", "DOUBLE" -> {
                    if (value instanceof Number number) {
                        entity.getPropertyManager().addProperty(
                                new FloatProperty(Identifier.of(identifier), MAX_PROPERTY_VALUE, MIN_PROPERTY_VALUE, 0f),
                                number.floatValue());
                    }
                }
                case "INTEGER", "INT" -> {
                    if (value instanceof Number number) {
                        entity.getPropertyManager().addProperty(
                                new IntProperty(Identifier.of(identifier), MAX_PROPERTY_VALUE, MIN_PROPERTY_VALUE, 0),
                                number.intValue());
                    }
                }
                default -> {
                }
            }
        } catch (RuntimeException exception) {
            if (!warnedRuntimePropertyUpdateFailure) {
                warnedRuntimePropertyUpdateFailure = true;
                logger().warning("Could not apply RSPM custom Bedrock entity property update for "
                        + identifier + ": " + exception.getMessage());
            }
        }
    }

    private String inferPropertyType(Object value) {
        if (value instanceof Boolean) {
            return "BOOLEAN";
        }
        if (value instanceof Float || value instanceof Double) {
            return "FLOAT";
        }
        if (value instanceof Number) {
            return "INTEGER";
        }
        return null;
    }

    private EntityDefinition<?> registerEntityToGeyser(BridgeEntityDefinition definition, boolean lateRegistration) {
        if (definition == null || definition.identifier() == null) {
            return null;
        }

        ensureIdentifierInRegistry(definition.identifier());
        EntityDefinition<?> existingDefinition = LOADED_DEFINITIONS.get(definition.identifier());
        boolean canBuildProperties = propertyDefinitionEventActive
                && !definition.properties().isEmpty()
                && Registries.BEDROCK_ENTITY_PROPERTIES.get().isEmpty();
        EntityDefinition<Entity> entityDefinition;

        if (canBuildProperties) {
            EntityDefinition.Builder<Entity> builder = entityDefinitionBuilder(definition);
            builder.propertiesBuilder(propertiesBuilder(definition));
            entityDefinition = builder.build(false);
        } else if (hasRegisteredProperties(existingDefinition)) {
            entityDefinition = new EntityDefinition<>(
                    Entity::new,
                    null,
                    definition.identifier(),
                    definition.width(),
                    definition.height(),
                    existingDefinition.offset(),
                    existingDefinition.registeredProperties(),
                    List.of());
        } else {
            entityDefinition = entityDefinitionBuilder(definition).build(false);
        }
        LOADED_DEFINITIONS.put(definition.identifier(), entityDefinition);

        if (lateRegistration && !warnedLateRegistryChange) {
            warnedLateRegistryChange = true;
            logger().warning("RSPM custom Bedrock entity definitions arrived after Geyser initialized. "
                    + "The bridge updated the in-memory registry, but affected Bedrock clients may need to reconnect "
                    + "or the server may need a restart if Geyser has already sent its registry shape.");
        }
        if (geyserLoaded && !canBuildProperties && !hasRegisteredProperties(entityDefinition)
                && !definition.properties().isEmpty() && !warnedLatePropertiesUnavailable) {
            warnedLatePropertiesUnavailable = true;
            logger().warning("RSPM custom Bedrock entity properties arrived after Geyser's property-definition event. "
                    + "Custom property updates for these entities require the Bedrock pack to be available before "
                    + "Geyser initializes; restart the server after pack generation.");
        }
        return entityDefinition;
    }

    private EntityDefinition.Builder<Entity> entityDefinitionBuilder(BridgeEntityDefinition definition) {
        return EntityDefinition.<Entity>builder(Entity::new)
                .height(definition.height())
                .width(definition.width())
                .identifier(definition.identifier());
    }

    private boolean hasRegisteredProperties(EntityDefinition<?> entityDefinition) {
        return entityDefinition != null
                && entityDefinition.registeredProperties() != null
                && !entityDefinition.registeredProperties().isEmpty();
    }

    private void installPropertyRegistryEntries(List<EntityDefinition<?>> entityDefinitions) {
        if (entityDefinitions.isEmpty()) {
            return;
        }

        try {
            Set<NbtMap> propertyRegistry = Registries.BEDROCK_ENTITY_PROPERTIES.get();
            int installed = 0;
            for (EntityDefinition<?> entityDefinition : entityDefinitions) {
                String identifier = entityDefinition.identifier();
                propertyRegistry.removeIf(entry -> entry.containsKey("type")
                        && identifier.equals(entry.getString("type")));
                propertyRegistry.add(entityDefinition.registeredProperties().toNbtMap(identifier));
                installed++;
            }
            logger().info("Registered " + installed + " custom Bedrock entity property schema(s) with Geyser.");
        } catch (RuntimeException exception) {
            if (!warnedPropertyRegistryInstallFailure) {
                warnedPropertyRegistryInstallFailure = true;
                logger().warning("Could not install RSPM custom Bedrock entity property registry entries: "
                        + exception.getMessage());
            }
        }
    }

    private void ensureIdentifierInRegistry(String identifier) {
        NbtMap registry = Registries.BEDROCK_ENTITY_IDENTIFIERS.get();
        List<NbtMap> idList = new ArrayList<>(registry.getList("idlist", NbtType.COMPOUND));
        for (NbtMap entry : idList) {
            if (identifier.equals(entry.getString("id"))) {
                return;
            }
        }

        int nextRuntimeId = idList.stream()
                .mapToInt(entry -> entry.getInt("rid"))
                .max()
                .orElse(idList.size()) + 1;
        idList.add(NbtMap.builder()
                .putString("id", identifier)
                .putString("bid", "")
                .putBoolean("hasspawnegg", false)
                .putInt("rid", nextRuntimeId)
                .putBoolean("summonable", false)
                .build());

        Registries.BEDROCK_ENTITY_IDENTIFIERS.set(NbtMap.builder()
                .putList("idlist", NbtType.COMPOUND, idList)
                .build());
    }

    private GeyserEntityProperties.Builder propertiesBuilder(BridgeEntityDefinition definition) {
        GeyserEntityProperties.Builder builder = new GeyserEntityProperties.Builder(definition.identifier());
        for (BridgePropertyDefinition property : definition.properties()) {
            addProperty(builder, property);
        }
        return builder;
    }

    private void addProperty(GeyserEntityProperties.Builder builder, BridgePropertyDefinition property) {
        if (property == null || property.identifier() == null || property.type() == null) {
            return;
        }

        switch (property.type().toUpperCase(Locale.ROOT)) {
            case "BOOLEAN", "BOOL" -> builder.add(new BooleanProperty(Identifier.of(property.identifier()), false));
            case "FLOAT", "DOUBLE" -> builder.add(new FloatProperty(
                    Identifier.of(property.identifier()), MAX_PROPERTY_VALUE, MIN_PROPERTY_VALUE, 0f));
            case "INTEGER", "INT" -> builder.add(new IntProperty(
                    Identifier.of(property.identifier()), MAX_PROPERTY_VALUE, MIN_PROPERTY_VALUE, 0));
            default -> logger().warning("Unknown RSPM custom entity property type " + property.type()
                    + " for " + property.identifier());
        }
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
