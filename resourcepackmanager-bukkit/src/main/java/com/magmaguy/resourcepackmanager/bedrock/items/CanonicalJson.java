package com.magmaguy.resourcepackmanager.bedrock.items;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * JSON text with object keys sorted at every level. The server writes an item's components in
 * hash order, which can change between restarts; the mix identity needs the same text for the
 * same components, or every restart would reconvert the Bedrock pack.
 */
public final class CanonicalJson {

    private CanonicalJson() {
    }

    public static String write(JsonElement element) {
        StringBuilder out = new StringBuilder();
        append(out, element);
        return out.toString();
    }

    private static void append(StringBuilder out, JsonElement element) {
        if (element == null || element.isJsonNull()) {
            out.append("null");
        } else if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            List<String> keys = new ArrayList<>(object.keySet());
            keys.sort(null);
            out.append('{');
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) out.append(',');
                out.append(new com.google.gson.JsonPrimitive(keys.get(i))).append(':');
                append(out, object.get(keys.get(i)));
            }
            out.append('}');
        } else if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            out.append('[');
            for (int i = 0; i < array.size(); i++) {
                if (i > 0) out.append(',');
                append(out, array.get(i));
            }
            out.append(']');
        } else {
            out.append(element);
        }
    }
}
