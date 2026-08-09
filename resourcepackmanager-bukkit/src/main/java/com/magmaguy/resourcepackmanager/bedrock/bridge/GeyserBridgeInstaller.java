package com.magmaguy.resourcepackmanager.bedrock.bridge;

import com.magmaguy.easyminecraftgoals.customentity.BedrockCustomEntityBridgeRegistry;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.bridge.BridgeChannels;
import com.magmaguy.resourcepackmanager.bridge.UniversalPluginJarInstaller;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class GeyserBridgeInstaller {
    public static final String CHANNEL = BridgeChannels.CUSTOM_ENTITY;

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
        if (geyser != null) {
            installIntoLocalGeyser(geyser);
            return;
        }

        boolean floodgate = Bukkit.getPluginManager().getPlugin("floodgate") != null
                || Bukkit.getPluginManager().getPlugin("Floodgate") != null;
        if (floodgate) {
            Path exported = exportUniversalJarForExternalGeyser();
            ResourcePackManager.plugin.getLogger().warning(
                    "Floodgate is installed but Geyser is external. Custom Bedrock models require the "
                            + "RSPM Geyser extension in that Geyser process."
                            + (exported == null ? "" : " Copy the exact universal JAR at " + exported
                            + " into the external Geyser 'extensions' folder and restart Geyser.")
                            + " On proxy networks, install this same ResourcePackManager.jar on the proxy too. "
                            + "Setup: https://nightbreak.io/plugin/resourcepackmanager/");
        } else {
            ResourcePackManager.plugin.getLogger().info(
                    "Geyser-Spigot and Floodgate were not detected locally; skipping automatic Geyser extension installation.");
        }
    }

    private static void installIntoLocalGeyser(Plugin geyser) {
        Path extensionsDirectory = geyser.getDataFolder().toPath().resolve("extensions");
        try {
            UniversalPluginJarInstaller.Result result =
                    UniversalPluginJarInstaller.installRunningJar(
                            extensionsDirectory, ResourcePackManager.class);
            switch (result.state()) {
                case CURRENT -> {
                }
                case INSTALLED_DIRECTLY_RESTART_REQUIRED ->
                        ResourcePackManager.plugin.getLogger().warning(
                                "Installed the byte-identical universal ResourcePackManager.jar in Geyser-Spigot/extensions "
                                        + "(SHA-256 " + result.sha256() + "). Restart the server once so Geyser loads it.");
                case STAGED_FOR_GEYSER_RESTART ->
                        ResourcePackManager.plugin.getLogger().warning(
                                "Staged the byte-identical universal ResourcePackManager.jar through Geyser's update queue at "
                                        + result.staged() + ". Restart the server once; Geyser will replace every older RSPM extension before loading it.");
            }
        } catch (IOException exception) {
            ResourcePackManager.plugin.getLogger().warning(
                    "Failed to install the universal RSPM JAR for Geyser: " + exception.getMessage());
        }
    }

    /**
     * Stages the just-downloaded, checksum-verified universal update for a
     * locally hosted Geyser-Spigot instance. This avoids requiring a second
     * restart after Bukkit has already consumed its own update folder.
     */
    public static void stageDownloadedUpdate(Path updateJar) {
        Plugin geyser = Bukkit.getPluginManager().getPlugin("Geyser-Spigot");
        if (geyser == null || updateJar == null) return;
        try {
            UniversalPluginJarInstaller.Result result =
                    UniversalPluginJarInstaller.install(
                            updateJar, geyser.getDataFolder().toPath().resolve("extensions"));
            if (result.downgradePrevented()) {
                ResourcePackManager.plugin.getLogger().warning(
                        "Did not stage ResourcePackManager " + result.sourceVersion()
                                + " for Geyser because Geyser already has newer "
                                + result.retainedVersion() + ".");
            } else if (result.state() != UniversalPluginJarInstaller.State.CURRENT) {
                ResourcePackManager.plugin.getLogger().warning(
                        "Staged the downloaded ResourcePackManager " + result.sourceVersion()
                                + " update for local Geyser using the same SHA-256 "
                                + result.sha256() + ". One server restart will update both.");
            }
        } catch (IOException exception) {
            ResourcePackManager.plugin.getLogger().warning(
                    "Could not stage the downloaded ResourcePackManager update for local Geyser: "
                            + exception.getMessage());
        }
    }

    private static Path exportUniversalJarForExternalGeyser() {
        Path exportDirectory = ResourcePackManager.plugin.getDataFolder().toPath().resolve("geyser-extension");
        try {
            UniversalPluginJarInstaller.Result result =
                    UniversalPluginJarInstaller.installRunningJar(
                            exportDirectory, ResourcePackManager.class);
            // The installer stages replacements when an older universal export
            // already exists, just as it does for a loaded Geyser extension.
            // Returning installed() in that state told the administrator to
            // copy the stale root JAR instead of the newly verified update.
            return result.state() == UniversalPluginJarInstaller.State.STAGED_FOR_GEYSER_RESTART
                    ? result.staged()
                    : result.installed();
        } catch (IOException exception) {
            ResourcePackManager.plugin.getLogger().warning(
                    "Failed to export the universal RSPM JAR for external Geyser: "
                            + exception.getMessage());
            return null;
        }
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
