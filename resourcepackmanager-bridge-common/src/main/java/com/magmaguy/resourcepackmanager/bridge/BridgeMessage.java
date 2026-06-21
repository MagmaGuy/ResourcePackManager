package com.magmaguy.resourcepackmanager.bridge;

import java.util.LinkedHashMap;
import java.util.Map;

public class BridgeMessage {
    private BridgeMessageType type;
    private int entityId;
    private String identifier;
    private BridgeEntityDefinition definition;
    private Float height;
    private Float width;
    private Float scale;
    private Integer color;
    private Integer variant;
    private Map<String, Object> properties = new LinkedHashMap<>();

    public BridgeMessage() {
    }

    public static BridgeMessage registerDefinition(BridgeEntityDefinition definition) {
        BridgeMessage message = new BridgeMessage();
        message.type = BridgeMessageType.REGISTER_DEFINITION;
        message.identifier = definition.identifier();
        message.definition = definition;
        return message;
    }

    public static BridgeMessage setCustomEntity(int entityId, String identifier) {
        BridgeMessage message = new BridgeMessage();
        message.type = BridgeMessageType.SET_CUSTOM_ENTITY;
        message.entityId = entityId;
        message.identifier = identifier;
        return message;
    }

    public static BridgeMessage entityData(int entityId, Float height, Float width,
                                           Float scale, Integer color, Integer variant) {
        BridgeMessage message = new BridgeMessage();
        message.type = BridgeMessageType.ENTITY_DATA;
        message.entityId = entityId;
        message.height = height;
        message.width = width;
        message.scale = scale;
        message.color = color;
        message.variant = variant;
        return message;
    }

    public static BridgeMessage properties(int entityId, Map<String, ?> properties) {
        BridgeMessage message = new BridgeMessage();
        message.type = BridgeMessageType.PROPERTIES;
        message.entityId = entityId;
        if (properties != null) {
            properties.forEach((key, value) -> message.properties.put(key, value));
        }
        return message;
    }

    public BridgeMessageType type() {
        return type;
    }

    public int entityId() {
        return entityId;
    }

    public String identifier() {
        return identifier;
    }

    public BridgeEntityDefinition definition() {
        return definition;
    }

    public Float height() {
        return height;
    }

    public Float width() {
        return width;
    }

    public Float scale() {
        return scale;
    }

    public Integer color() {
        return color;
    }

    public Integer variant() {
        return variant;
    }

    public Map<String, Object> properties() {
        return properties == null ? Map.of() : Map.copyOf(properties);
    }
}
