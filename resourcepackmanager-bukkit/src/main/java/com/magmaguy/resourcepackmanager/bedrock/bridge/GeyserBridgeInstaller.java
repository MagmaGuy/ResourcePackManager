package com.magmaguy.resourcepackmanager.bedrock.bridge;

import com.magmaguy.easyminecraftgoals.customentity.BedrockCustomEntityBridgeRegistry;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.bridge.BridgeChannels;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class GeyserBridgeInstaller {
    public static final String CHANNEL = BridgeChannels.CUSTOM_ENTITY;

    private static final String BRIDGE_RESOURCE = "geyser-extension/ResourcePackManager-GeyserBridge.jar";
    private static final String BRIDGE_FILE_NAME = "ResourcePackManager-GeyserBridge.jar";
    private static final List<String> RELOCATED_CUSTOM_ENTITY_PACKAGES = List.of(
            "com.magmaguy.freeminecraftmodels.easyminecraftgoals.customentity",
            "com.magmaguy.elitemobs.easyminecraftgoals.customentity"
    );
    private static boolean registered;
    private static RspmBukkitBedrockCustomEntityBridge bridge;
    private static final List<ReflectiveBridgeRegistration> reflectiveBridgeRegistrations = new ArrayList<>();

    private GeyserBridgeInstaller() {
    }

    public static void register() {
        if (registered || ResourcePackManager.plugin == null) {
            return;
        }

        Bukkit.getMessenger().registerOutgoingPluginChannel(ResourcePackManager.plugin, CHANNEL);
        bridge = new RspmBukkitBedrockCustomEntityBridge();
        BedrockCustomEntityBridgeRegistry.register(bridge);
        registerRelocatedBridgeRegistries();
        installBundledExtension();
        registered = true;
    }

    public static void unregister() {
        if (!registered || ResourcePackManager.plugin == null) {
            return;
        }

        BedrockCustomEntityBridgeRegistry.unregister(bridge);
        bridge = null;
        unregisterRelocatedBridgeRegistries();
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(ResourcePackManager.plugin, CHANNEL);
        registered = false;
    }

    private static void registerRelocatedBridgeRegistries() {
        for (String packageName : RELOCATED_CUSTOM_ENTITY_PACKAGES) {
            try {
                Class<?> registryClass = Class.forName(packageName + ".BedrockCustomEntityBridgeRegistry");
                Class<?> bridgeInterface = Class.forName(packageName + ".BedrockCustomEntityBridge");
                Object proxy = Proxy.newProxyInstance(
                        bridgeInterface.getClassLoader(),
                        new Class[]{bridgeInterface},
                        new RelocatedBridgeInvocationHandler());
                Method register = registryClass.getMethod("register", bridgeInterface);
                register.invoke(null, proxy);
                reflectiveBridgeRegistrations.add(new ReflectiveBridgeRegistration(registryClass, bridgeInterface, proxy));
            } catch (ClassNotFoundException ignored) {
                // Optional shaded consumers are not installed or not loaded yet.
            } catch (Throwable throwable) {
                ResourcePackManager.plugin.getLogger().warning(
                        "Failed to register RSPM custom entity bridge for relocated package "
                                + packageName + ": " + throwable.getMessage());
            }
        }
    }

    private static void unregisterRelocatedBridgeRegistries() {
        for (ReflectiveBridgeRegistration registration : reflectiveBridgeRegistrations) {
            try {
                Method unregister = registration.registryClass().getMethod("unregister", registration.bridgeInterface());
                unregister.invoke(null, registration.proxy());
            } catch (Throwable ignored) {
                // Best-effort cleanup during shutdown/reload.
            }
        }
        reflectiveBridgeRegistrations.clear();
    }

    private static void installBundledExtension() {
        Plugin geyser = Bukkit.getPluginManager().getPlugin("Geyser-Spigot");
        if (geyser == null) {
            ResourcePackManager.plugin.getLogger().info(
                    "Geyser-Spigot is not installed locally; RSPM custom Bedrock entity bridge messages are still registered for proxy-side Geyser extensions.");
            return;
        }

        Path extensionsDirectory = geyser.getDataFolder().toPath().resolve("extensions");
        Path extensionFile = extensionsDirectory.resolve(BRIDGE_FILE_NAME);
        try (InputStream inputStream = ResourcePackManager.plugin.getResource(BRIDGE_RESOURCE)) {
            if (inputStream == null) {
                ResourcePackManager.plugin.getLogger().warning(
                        "Bundled RSPM Geyser bridge extension jar is missing from the plugin jar.");
                return;
            }

            byte[] bundledBytes = inputStream.readAllBytes();
            Files.createDirectories(extensionsDirectory);
            boolean changed = !Files.isRegularFile(extensionFile)
                    || !Arrays.equals(sha256(extensionFile), sha256(bundledBytes));
            if (!changed) {
                return;
            }

            Files.write(extensionFile, bundledBytes);
            ResourcePackManager.plugin.getLogger().warning(
                    "Installed or updated " + BRIDGE_FILE_NAME + " in Geyser-Spigot/extensions. "
                            + "Restart the server so Geyser loads the RSPM custom entity bridge before Bedrock players join.");
        } catch (IOException | NoSuchAlgorithmException exception) {
            ResourcePackManager.plugin.getLogger().warning(
                    "Failed to install RSPM Geyser bridge extension: " + exception.getMessage());
        }
    }

    private static byte[] sha256(Path path) throws IOException, NoSuchAlgorithmException {
        return sha256(Files.readAllBytes(path));
    }

    private static byte[] sha256(byte[] bytes) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return digest.digest(bytes);
    }

    private record ReflectiveBridgeRegistration(Class<?> registryClass, Class<?> bridgeInterface, Object proxy) {
    }

    private static final class RelocatedBridgeInvocationHandler implements InvocationHandler {
        private final ConcurrentMap<String, com.magmaguy.resourcepackmanager.bridge.BridgeEntityDefinition> definitions =
                new ConcurrentHashMap<>();

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "isAvailable" -> ResourcePackManager.plugin != null && ResourcePackManager.plugin.isEnabled();
                case "registerDefinition" -> {
                    if (args != null && args.length == 1) {
                        registerDefinition(args[0]);
                    }
                    yield null;
                }
                case "prepareEntitySpawn" -> {
                    if (args != null && args.length == 3 && args[0] instanceof org.bukkit.entity.Player player) {
                        prepareEntitySpawn(player, (Integer) args[1], args[2]);
                    }
                    yield null;
                }
                case "sendEntityData" -> {
                    if (args != null && args.length == 7 && args[0] instanceof org.bukkit.entity.Player player) {
                        send(player, com.magmaguy.resourcepackmanager.bridge.BridgeMessage.entityData(
                                (Integer) args[1],
                                (Float) args[2],
                                (Float) args[3],
                                (Float) args[4],
                                (Integer) args[5],
                                (Integer) args[6]));
                    }
                    yield null;
                }
                case "sendProperties" -> {
                    if (args != null && args.length == 3 && args[0] instanceof org.bukkit.entity.Player player
                            && args[2] instanceof Map<?, ?> map) {
                        send(player, com.magmaguy.resourcepackmanager.bridge.BridgeMessage.properties(
                                (Integer) args[1], normalizePropertyMap(map)));
                    }
                    yield null;
                }
                default -> defaultReturn(method.getReturnType());
            };
        }

        private void registerDefinition(Object definition) {
            com.magmaguy.resourcepackmanager.bridge.BridgeEntityDefinition bridgeDefinition = convert(definition);
            if (bridgeDefinition != null) {
                definitions.putIfAbsent(bridgeDefinition.identifier(), bridgeDefinition);
            }
        }

        private void prepareEntitySpawn(org.bukkit.entity.Player player, int javaEntityId, Object definition) {
            com.magmaguy.resourcepackmanager.bridge.BridgeEntityDefinition bridgeDefinition = convert(definition);
            if (bridgeDefinition == null) {
                return;
            }
            definitions.putIfAbsent(bridgeDefinition.identifier(), bridgeDefinition);
            send(player, com.magmaguy.resourcepackmanager.bridge.BridgeMessage.registerDefinition(bridgeDefinition));
            send(player, com.magmaguy.resourcepackmanager.bridge.BridgeMessage.setCustomEntity(
                    javaEntityId, bridgeDefinition.identifier()));
        }

        private com.magmaguy.resourcepackmanager.bridge.BridgeEntityDefinition convert(Object definition) {
            try {
                String identifier = (String) definition.getClass().getMethod("identifier").invoke(definition);
                float width = ((Number) definition.getClass().getMethod("width").invoke(definition)).floatValue();
                float height = ((Number) definition.getClass().getMethod("height").invoke(definition)).floatValue();
                Object schema = definition.getClass().getMethod("propertySchema").invoke(definition);
                List<com.magmaguy.resourcepackmanager.bridge.BridgePropertyDefinition> properties = new ArrayList<>();
                if (schema != null) {
                    Object rawProperties = schema.getClass().getMethod("properties").invoke(schema);
                    if (rawProperties instanceof Iterable<?> iterable) {
                        for (Object property : iterable) {
                            String propertyIdentifier = (String) property.getClass().getMethod("identifier").invoke(property);
                            Object type = property.getClass().getMethod("type").invoke(property);
                            String typeName = String.valueOf(type);
                            if ("STRING".equals(typeName)) {
                                ResourcePackManager.plugin.getLogger().warning(
                                        "Skipping unsupported Bedrock custom entity string property "
                                                + propertyIdentifier + " for " + identifier);
                                continue;
                            }
                            properties.add(new com.magmaguy.resourcepackmanager.bridge.BridgePropertyDefinition(
                                    propertyIdentifier, typeName));
                        }
                    }
                }
                return new com.magmaguy.resourcepackmanager.bridge.BridgeEntityDefinition(
                        identifier, width, height, properties);
            } catch (Throwable throwable) {
                ResourcePackManager.plugin.getLogger().warning(
                        "Failed to convert relocated custom entity definition: " + throwable.getMessage());
                return null;
            }
        }

        private void send(org.bukkit.entity.Player player, com.magmaguy.resourcepackmanager.bridge.BridgeMessage message) {
            if (player == null || !player.isOnline() || ResourcePackManager.plugin == null || !ResourcePackManager.plugin.isEnabled()) {
                return;
            }
            player.sendPluginMessage(ResourcePackManager.plugin, CHANNEL,
                    com.magmaguy.resourcepackmanager.bridge.BridgeCodec.encode(message));
        }

        private Map<String, Object> normalizePropertyMap(Map<?, ?> map) {
            Map<String, Object> normalized = new java.util.LinkedHashMap<>();
            map.forEach((key, value) -> {
                if (key != null) {
                    normalized.put(String.valueOf(key), value);
                }
            });
            return normalized;
        }

        private Object defaultReturn(Class<?> returnType) {
            if (returnType == boolean.class) {
                return false;
            }
            if (returnType == int.class || returnType == short.class || returnType == byte.class || returnType == long.class) {
                return 0;
            }
            if (returnType == float.class || returnType == double.class) {
                return 0.0;
            }
            return null;
        }
    }
}
