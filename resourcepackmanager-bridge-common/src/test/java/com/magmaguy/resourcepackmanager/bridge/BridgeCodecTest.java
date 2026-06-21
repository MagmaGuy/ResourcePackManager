package com.magmaguy.resourcepackmanager.bridge;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BridgeCodecTest {
    @Test
    void definitionRoundTrips() {
        BridgeEntityDefinition definition = new BridgeEntityDefinition(
                "fmm:test",
                0.5f,
                1.25f,
                List.of(new BridgePropertyDefinition("fmm:bone0", "INT")));

        BridgeMessage decoded = BridgeCodec.decode(BridgeCodec.encode(BridgeMessage.registerDefinition(definition)));

        assertEquals(BridgeMessageType.REGISTER_DEFINITION, decoded.type());
        assertEquals("fmm:test", decoded.definition().identifier());
        assertEquals("fmm:bone0", decoded.definition().properties().get(0).identifier());
    }

    @Test
    void propertiesRoundTrip() {
        BridgeMessage decoded = BridgeCodec.decode(BridgeCodec.encode(
                BridgeMessage.properties(42, Map.of("fmm:anim0", 3))));

        assertEquals(BridgeMessageType.PROPERTIES, decoded.type());
        assertEquals(42, decoded.entityId());
        assertEquals(3.0, decoded.properties().get("fmm:anim0"));
    }
}
