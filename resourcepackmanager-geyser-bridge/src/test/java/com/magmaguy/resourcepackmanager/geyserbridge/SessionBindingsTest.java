package com.magmaguy.resourcepackmanager.geyserbridge;

import com.magmaguy.resourcepackmanager.bridge.BridgeCodec;
import com.magmaguy.resourcepackmanager.bridge.BridgeMessage;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * What the session keeps beside a binding. Geyser builds a new entity each time the server spawns a mob, with
 * default properties and no data, and the plugin sends an entity's data and properties only when it first shows the
 * entity and when a value changes. So a mob the server spawns again needs what was sent before.
 */
class SessionBindingsTest {
    private static final String SEWER_RAT = "freeminecraftmodels:em_ag_sewerrat";
    private static final String PENGUIN = "freeminecraftmodels:fmm_penguin";
    private static final String ANIMATIONS = "freeminecraftmodels:anim0";
    private static final String LEAN = "freeminecraftmodels:lean_x";
    private static final int MOB = 194;

    private final SessionBindings bindings = new SessionBindings();

    /** A message as the extension receives it: encoded by the plugin, decoded here. */
    private static BridgeMessage received(BridgeMessage sent) {
        return BridgeCodec.decode(BridgeCodec.encode(sent));
    }

    private void properties(int entityId, Object... namesAndValues) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (int index = 0; index < namesAndValues.length; index += 2)
            properties.put((String) namesAndValues[index], namesAndValues[index + 1]);
        bindings.remember(received(BridgeMessage.properties(entityId, properties)));
    }

    private void entityData(int entityId, Float height, Float width, Float scale) {
        bindings.remember(received(BridgeMessage.entityData(entityId, height, width, scale, null, null)));
    }

    private static int number(Object value) {
        return ((Number) value).intValue();
    }

    @Test
    void keepsTheDataAndPropertiesSentForABoundEntity() {
        // The plugin sends them one tick before the mob's spawn, so no Geyser entity exists yet to take them.
        bindings.bind(MOB, SEWER_RAT);
        entityData(MOB, 1.4375f, 1f, 2.5f);
        properties(MOB, ANIMATIONS, 4);

        SessionBindings.Binding binding = bindings.of(MOB);

        assertEquals(new SessionBindings.EntityData(1.4375f, 1f, 2.5f, null, null), binding.entityData());
        assertEquals(1, binding.properties().size(), "One property was sent");
        assertEquals(4, number(binding.properties().get(ANIMATIONS)), "The animation flags the plugin sent last");
    }

    @Test
    void laterMessagesChangeOnlyWhatTheyName() {
        bindings.bind(MOB, SEWER_RAT);
        entityData(MOB, 1.4375f, 1f, null);
        entityData(MOB, null, null, 2.5f);
        properties(MOB, ANIMATIONS, 1, LEAN, 300);
        properties(MOB, ANIMATIONS, 4);

        SessionBindings.Binding binding = bindings.of(MOB);

        assertEquals(new SessionBindings.EntityData(1.4375f, 1f, 2.5f, null, null), binding.entityData(),
                "A field a message leaves out keeps the value sent before, as it does on the Geyser entity");
        assertEquals(4, number(binding.properties().get(ANIMATIONS)));
        assertEquals(300, number(binding.properties().get(LEAN)), "A property a message leaves out keeps its value");
    }

    @Test
    void bindingTheSameDefinitionAgainKeepsWhatWasSent() {
        // The plugin binds again each time it shows the mob to the viewer, and sends the state after it.
        bindings.bind(MOB, SEWER_RAT);
        properties(MOB, ANIMATIONS, 4);

        bindings.bind(MOB, SEWER_RAT);

        assertEquals(1, bindings.of(MOB).properties().size(), "The property sent before the second binding");
        assertEquals(4, number(bindings.of(MOB).properties().get(ANIMATIONS)));
    }

    @Test
    void aBindingToAnotherDefinitionStartsWithoutTheOldOnesState() {
        // A mob whose model is swapped: the old model's properties mean nothing to the new definition.
        bindings.bind(MOB, SEWER_RAT);
        entityData(MOB, 1.4375f, 1f, 2.5f);
        properties(MOB, ANIMATIONS, 4);

        bindings.bind(MOB, PENGUIN);

        assertEquals(PENGUIN, bindings.of(MOB).identifier());
        assertEquals(SessionBindings.EntityData.NONE, bindings.of(MOB).entityData());
        assertEquals(Map.of(), bindings.of(MOB).properties());
    }

    @Test
    void keepsNothingForAnEntityThatIsNotBound() {
        properties(MOB, ANIMATIONS, 4);
        entityData(MOB, 1.4375f, 1f, 2.5f);

        assertNull(bindings.of(MOB), "A message about an entity with no binding leaves nothing behind");
    }

    @Test
    void whatWasSentEndsWithTheBinding() {
        bindings.bind(MOB, SEWER_RAT);
        properties(MOB, ANIMATIONS, 4);
        bindings.bind(MOB + 1, SEWER_RAT);
        properties(MOB + 1, ANIMATIONS, 4);

        bindings.unbind(MOB);
        assertNull(bindings.of(MOB));
        bindings.bind(MOB, SEWER_RAT);
        assertEquals(Map.of(), bindings.of(MOB).properties(), "A new binding of the id does not see the old state");

        bindings.clear();
        assertNull(bindings.of(MOB));
        assertNull(bindings.of(MOB + 1), "A login or the session's end leaves nothing of any entity");
    }
}
