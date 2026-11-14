package com.magmaguy.resourcepackmanager.bedrock.generic;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Objects;

/**
 * The data components of one plugin-registered custom item, read from a freshly built stack on
 * the server. Bedrock fixes an item's properties when the player joins, so the Geyser mapping
 * for the item's model has to carry every component the item changes against its base item.
 *
 * @param source    the plugin the item comes from, such as {@code CraftEngine}
 * @param itemId    the plugin's id for the item, such as {@code default:topaz}
 * @param baseItem  the Java item the client receives, such as {@code minecraft:nether_brick}
 * @param patch     the stack's changes against its base item, keyed by component id; a removed
 *                  base component is written {@code "!minecraft:food": {}}
 * @param prototype every component of the plain base item. It is kept as given, not copied: the
 *                  collector shares one prototype among every item on the same base item, and
 *                  nothing may modify it after the snapshot is built.
 */
public record ItemComponentSnapshot(String source, String itemId, String baseItem,
                                    JsonObject patch, JsonObject prototype) {
    public ItemComponentSnapshot {
        Objects.requireNonNull(baseItem, "baseItem");
        patch = patch == null ? new JsonObject() : patch.deepCopy();
        prototype = prototype == null ? new JsonObject() : prototype;
    }

    /** The {@code minecraft:item_model} the stack sets, or {@code null} when it keeps its base's. */
    public String itemModel() {
        JsonElement model = patch.get("minecraft:item_model");
        return model != null && model.isJsonPrimitive() ? model.getAsString() : null;
    }

    /**
     * The first custom-model-data float the stack sets, or {@code null}. 1.21.4 and newer write
     * {@code {"floats": [n]}}; a bare number is accepted for older data.
     */
    public Float customModelData() {
        JsonElement data = patch.get("minecraft:custom_model_data");
        if (data == null) return null;
        try {
            if (data.isJsonPrimitive()) return data.getAsFloat();
            if (data.isJsonObject() && data.getAsJsonObject().has("floats")
                    && data.getAsJsonObject().get("floats").isJsonArray()
                    && !data.getAsJsonObject().getAsJsonArray("floats").isEmpty()) {
                return data.getAsJsonObject().getAsJsonArray("floats").get(0).getAsFloat();
            }
        } catch (RuntimeException unreadable) {
            return null;
        }
        return null;
    }

    /**
     * The component as the item effectively has it: the patch's value, nothing when the patch
     * removes it, otherwise the base item's.
     */
    public JsonElement effective(String component) {
        if (patch.has("!" + component)) return null;
        if (patch.has(component)) return patch.get(component);
        return prototype.get(component);
    }

    /** True when the stack's value differs from what the base item would give it. */
    public boolean changes(String component) {
        return patch.has(component) || patch.has("!" + component);
    }
}
