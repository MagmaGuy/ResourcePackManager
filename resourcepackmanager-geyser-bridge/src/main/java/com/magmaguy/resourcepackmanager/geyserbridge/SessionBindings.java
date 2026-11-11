package com.magmaguy.resourcepackmanager.geyserbridge;

import com.magmaguy.resourcepackmanager.bridge.BridgeMessage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * One Bedrock session's custom entity bindings: the Java entity ids that are drawn with a custom Bedrock
 * definition, each with the entity data and the properties its plugin sent last.
 *
 * <p>The data and properties are kept because Geyser builds a new entity each time the server spawns a mob, with
 * default properties and no data, while the plugin sends them only when it first shows the mob to a viewer and when
 * a value changes. A mob the server spawns again on its own, after the viewer's death or a walk out of its tracking
 * range and back, would otherwise stand in its rest pose at scale 1 until its next change. They are also what the
 * first spawn needs: the plugin sends them one tick before it, when no Geyser entity exists yet to take them.</p>
 *
 * <p>Everything held for an entity lives in its binding, so it ends where the binding ends: at an unbind, and for
 * all entities when the bindings are cleared at a login or dropped with the session.</p>
 */
final class SessionBindings {
    /** The entity data a plugin sent for an entity; a null field was never sent. */
    record EntityData(Float height, Float width, Float scale, Integer color, Integer variant) {
        static final EntityData NONE = new EntityData(null, null, null, null, null);

        /** This data with every field the message names replaced, as on a Geyser entity that takes the message. */
        EntityData with(BridgeMessage message) {
            return new EntityData(
                    message.height() != null ? message.height() : height,
                    message.width() != null ? message.width() : width,
                    message.scale() != null ? message.scale() : scale,
                    message.color() != null ? message.color() : color,
                    message.variant() != null ? message.variant() : variant);
        }
    }

    /** What is held for one entity id. The properties are as decoded: a number is whatever the codec made of it. */
    record Binding(String identifier, EntityData entityData, Map<String, Object> properties) {
        /** Whether a spawn of the entity has anything to be given besides its definition. */
        boolean hasState() {
            return !entityData.equals(EntityData.NONE) || !properties.isEmpty();
        }
    }

    private final ConcurrentMap<Integer, Binding> bindings = new ConcurrentHashMap<>();

    /**
     * Draws the entity with this custom definition from its next spawn on. A binding to the definition it already
     * has keeps what was sent for it; a binding to another definition starts empty, since the old one's properties
     * mean nothing to the new.
     */
    void bind(int entityId, String identifier) {
        // Every mob that wears a model names the same definition, so they share one copy of its identifier.
        bindings.compute(entityId, (id, held) -> held != null && held.identifier().equals(identifier)
                ? held : new Binding(identifier.intern(), EntityData.NONE, Map.of()));
    }

    void unbind(int entityId) {
        bindings.remove(entityId);
    }

    void clear() {
        bindings.clear();
    }

    /** The entity's binding, or null when it is drawn as the plain mob. */
    Binding of(int entityId) {
        return bindings.get(entityId);
    }

    /**
     * Keeps what an entity data or properties message says about a bound entity, for the entity's next spawn. A
     * message changes only the fields and properties it names. Any other message, and a message about an entity
     * with no binding, leaves nothing behind.
     */
    void remember(BridgeMessage message) {
        if (message == null || message.type() == null) return;
        switch (message.type()) {
            case ENTITY_DATA -> bindings.computeIfPresent(message.entityId(), (id, held) ->
                    new Binding(held.identifier(), held.entityData().with(message), held.properties()));
            case PROPERTIES -> {
                Map<String, Object> sent = message.properties();
                if (sent.isEmpty()) return;
                bindings.computeIfPresent(message.entityId(), (id, held) -> {
                    Map<String, Object> merged = new LinkedHashMap<>(held.properties());
                    // The names are a model's few property identifiers, repeated for every mob that wears it.
                    sent.forEach((name, value) -> merged.put(name.intern(), value));
                    return new Binding(held.identifier(), held.entityData(), Map.copyOf(merged));
                });
            }
            default -> {
            }
        }
    }
}
