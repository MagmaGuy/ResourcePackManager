package com.magmaguy.resourcepackmanager.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;

public final class BridgeCodec {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private BridgeCodec() {
    }

    public static byte[] encode(BridgeMessage message) {
        return GSON.toJson(message).getBytes(StandardCharsets.UTF_8);
    }

    public static BridgeMessage decode(byte[] payload) {
        return GSON.fromJson(new String(payload, StandardCharsets.UTF_8), BridgeMessage.class);
    }
}
