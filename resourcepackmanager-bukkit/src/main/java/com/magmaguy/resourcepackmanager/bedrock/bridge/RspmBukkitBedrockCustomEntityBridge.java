package com.magmaguy.resourcepackmanager.bedrock.bridge;

import com.magmaguy.easyminecraftgoals.customentity.BedrockCustomEntityBridge;
import com.magmaguy.easyminecraftgoals.customentity.CustomEntityDefinition;
import com.magmaguy.easyminecraftgoals.customentity.CustomEntityPropertyDefinition;
import com.magmaguy.easyminecraftgoals.customentity.CustomEntityPropertyType;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.bridge.BridgeCodec;
import com.magmaguy.resourcepackmanager.bridge.BridgeEntityDefinition;
import com.magmaguy.resourcepackmanager.bridge.BridgeMessage;
import com.magmaguy.resourcepackmanager.bridge.BridgePropertyDefinition;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class RspmBukkitBedrockCustomEntityBridge implements BedrockCustomEntityBridge {
    private final ConcurrentMap<String, BridgeEntityDefinition> definitions = new ConcurrentHashMap<>();

    @Override
    public boolean isAvailable() {
        return ResourcePackManager.plugin != null && ResourcePackManager.plugin.isEnabled();
    }

    @Override
    public void registerDefinition(CustomEntityDefinition definition) {
        if (definition == null) {
            return;
        }
        definitions.computeIfAbsent(definition.identifier(), ignored -> convert(definition));
    }

    @Override
    public void prepareEntitySpawn(Player player, int javaEntityId, CustomEntityDefinition definition) {
        if (!canSend(player, definition)) {
            return;
        }

        BridgeEntityDefinition bridgeDefinition = definitions.computeIfAbsent(definition.identifier(), ignored -> convert(definition));
        send(player, BridgeMessage.registerDefinition(bridgeDefinition));
        send(player, BridgeMessage.setCustomEntity(javaEntityId, definition.identifier()));
    }

    @Override
    public void sendEntityData(Player player, int javaEntityId, Float height, Float width,
                               Float scale, Integer color, Integer variant) {
        if (!canSend(player)) {
            return;
        }
        send(player, BridgeMessage.entityData(javaEntityId, height, width, scale, color, variant));
    }

    @Override
    public void sendProperties(Player player, int javaEntityId, Map<String, Object> properties) {
        if (!canSend(player) || properties == null || properties.isEmpty()) {
            return;
        }
        send(player, BridgeMessage.properties(javaEntityId, properties));
    }

    private boolean canSend(Player player, CustomEntityDefinition definition) {
        return definition != null && canSend(player);
    }

    private boolean canSend(Player player) {
        return player != null && player.isOnline() && isAvailable();
    }

    private BridgeEntityDefinition convert(CustomEntityDefinition definition) {
        List<BridgePropertyDefinition> properties = new ArrayList<>();
        for (CustomEntityPropertyDefinition property : definition.propertySchema().properties()) {
            if (property.type() == CustomEntityPropertyType.STRING) {
                ResourcePackManager.plugin.getLogger().warning(
                        "Skipping unsupported Bedrock custom entity string property "
                                + property.identifier() + " for " + definition.identifier()
                                + "; use packed integer/boolean/float properties for runtime Geyser updates.");
                continue;
            }
            properties.add(new BridgePropertyDefinition(property.identifier(), property.type().name()));
        }
        return new BridgeEntityDefinition(definition.identifier(), definition.width(), definition.height(), properties);
    }

    private void send(Player player, BridgeMessage message) {
        player.sendPluginMessage(
                ResourcePackManager.plugin,
                GeyserBridgeInstaller.CHANNEL,
                BridgeCodec.encode(message));
    }
}
