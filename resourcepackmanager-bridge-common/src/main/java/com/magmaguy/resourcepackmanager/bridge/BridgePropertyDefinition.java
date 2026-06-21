package com.magmaguy.resourcepackmanager.bridge;

public class BridgePropertyDefinition {
    private String identifier;
    private String type;

    public BridgePropertyDefinition() {
    }

    public BridgePropertyDefinition(String identifier, String type) {
        this.identifier = identifier;
        this.type = type;
    }

    public String identifier() {
        return identifier;
    }

    public String type() {
        return type;
    }
}
