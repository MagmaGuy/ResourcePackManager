package com.magmaguy.resourcepackmanager.bridge;

import java.util.ArrayList;
import java.util.List;

public class BridgeEntityDefinition {
    private String identifier;
    private float width;
    private float height;
    private List<BridgePropertyDefinition> properties = new ArrayList<>();

    public BridgeEntityDefinition() {
    }

    public BridgeEntityDefinition(String identifier, float width, float height,
                                  List<BridgePropertyDefinition> properties) {
        this.identifier = identifier;
        this.width = width;
        this.height = height;
        this.properties = properties == null ? new ArrayList<>() : new ArrayList<>(properties);
    }

    public String identifier() {
        return identifier;
    }

    public float width() {
        return width;
    }

    public float height() {
        return height;
    }

    public List<BridgePropertyDefinition> properties() {
        return properties == null ? List.of() : List.copyOf(properties);
    }
}
