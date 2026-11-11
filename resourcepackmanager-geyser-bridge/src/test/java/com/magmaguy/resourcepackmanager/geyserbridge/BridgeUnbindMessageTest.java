package com.magmaguy.resourcepackmanager.geyserbridge;

import com.magmaguy.resourcepackmanager.bridge.BridgeCodec;
import com.magmaguy.resourcepackmanager.bridge.BridgeMessage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BridgeUnbindMessageTest {
    private static BridgeMessage decode(String json) {
        return BridgeCodec.decode(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void readsAnUnbindOfOneEntity() {
        BridgeMessage message = decode("{\"type\":\"CLEAR_CUSTOM_ENTITY\",\"entityId\":194}");

        assertEquals("CLEAR_CUSTOM_ENTITY", String.valueOf(message.type()));
        assertEquals(194, message.entityId());
    }

    @Test
    void readsAMessageTypeItDoesNotKnowAsNoType() {
        // What lets a newer plugin send a new message to an extension that is older than it: the extension
        // ignores a message with no type.
        BridgeMessage message = decode("{\"type\":\"SENT_BY_A_NEWER_PLUGIN\",\"entityId\":194}");

        assertNull(message.type());
    }
}
