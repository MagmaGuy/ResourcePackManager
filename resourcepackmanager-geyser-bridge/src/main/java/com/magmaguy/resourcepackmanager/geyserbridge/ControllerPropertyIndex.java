package com.magmaguy.resourcepackmanager.geyserbridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the entity properties a client entity's controllers query. Controllers are matched by
 * the identifiers the client entity references, not by file name: the bundle importer renames
 * long controller paths to short hashes, so a file-name lookup misses every model whose ID is
 * long enough, and Geyser then registers that entity without its properties.
 */
final class ControllerPropertyIndex {
    private static final Pattern QUERY_PROPERTY = Pattern.compile("query\\.property\\(['\"]([^'\"]+)['\"]\\)");

    private final Map<String, Set<String>> propertiesByController = new LinkedHashMap<>();

    /** Indexes every animation and render controller in one controller file. */
    void addControllerFile(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        for (String section : new String[]{"animation_controllers", "render_controllers"}) {
            if (!root.has(section) || !root.get(section).isJsonObject()) continue;
            for (Map.Entry<String, JsonElement> controller : root.getAsJsonObject(section).entrySet()) {
                Set<String> properties = propertiesByController.computeIfAbsent(controller.getKey(), ignored -> new LinkedHashSet<>());
                collect(controller.getValue(), properties);
            }
        }
    }

    /** Properties queried by the controllers this client entity description references. */
    Set<String> propertiesFor(JsonObject description) {
        Set<String> properties = new LinkedHashSet<>();
        for (String controller : referencedControllers(description)) {
            properties.addAll(propertiesByController.getOrDefault(controller, Set.of()));
        }
        return properties;
    }

    /** Properties queried anywhere in a file, for a controller file found by its legacy name. */
    static Set<String> propertiesIn(String json) {
        Set<String> properties = new LinkedHashSet<>();
        collect(JsonParser.parseString(json), properties);
        return properties;
    }

    static Set<String> referencedControllers(JsonObject description) {
        Set<String> controllers = new LinkedHashSet<>();
        if (description.has("animations") && description.get("animations").isJsonObject()) {
            for (Map.Entry<String, JsonElement> animation : description.getAsJsonObject("animations").entrySet()) {
                if (animation.getValue().isJsonPrimitive()) controllers.add(animation.getValue().getAsString());
            }
        }
        // Older client entities list controllers in their own array of short name to identifier maps.
        addIdentifiers(description.get("animation_controllers"), controllers);
        addIdentifiers(description.get("render_controllers"), controllers);
        return controllers;
    }

    private static void addIdentifiers(JsonElement element, Set<String> controllers) {
        if (element == null || !element.isJsonArray()) return;
        for (JsonElement entry : element.getAsJsonArray()) {
            if (entry.isJsonPrimitive()) {
                controllers.add(entry.getAsString());
            } else if (entry.isJsonObject()) {
                for (Map.Entry<String, JsonElement> pair : entry.getAsJsonObject().entrySet()) {
                    // Render controllers map identifier to condition; animation controllers map short name to identifier.
                    controllers.add(pair.getKey());
                    if (pair.getValue().isJsonPrimitive()) controllers.add(pair.getValue().getAsString());
                }
            }
        }
    }

    private static void collect(JsonElement element, Set<String> properties) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonPrimitive()) {
            if (!element.getAsJsonPrimitive().isString()) return;
            Matcher matcher = QUERY_PROPERTY.matcher(element.getAsString());
            while (matcher.find()) {
                if (!matcher.group(1).isBlank()) properties.add(matcher.group(1));
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) collect(child, properties);
        } else {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                // Transition conditions are object keys in animation controllers.
                collect(new com.google.gson.JsonPrimitive(entry.getKey()), properties);
                collect(entry.getValue(), properties);
            }
        }
    }
}
