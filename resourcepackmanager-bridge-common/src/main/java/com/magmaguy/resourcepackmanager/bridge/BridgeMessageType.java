package com.magmaguy.resourcepackmanager.bridge;

public enum BridgeMessageType {
    REGISTER_DEFINITION,
    SET_CUSTOM_ENTITY,
    ENTITY_DATA,
    PROPERTIES,
    /**
     * Ends one entity's binding to its custom definition for the session. Without it a binding lasts until the
     * session's next login or its end, so that a mob the server stops tracking and tracks again keeps its model.
     * An extension from before this type reads it as no type and ignores the message.
     */
    CLEAR_CUSTOM_ENTITY
}
