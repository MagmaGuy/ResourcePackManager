package com.magmaguy.resourcepackmanager.bedrock;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Reads the Java base item of every Oraxen item from {@code plugins/Oraxen/items/}, for
 * {@link com.magmaguy.resourcepackmanager.bedrock.BedrockConverterContext#declaredBaseItems()}.
 *
 * <p>On 1.21.4+ Oraxen writes each textured item to {@code assets/oraxen/items/<id>.json}
 * and puts {@code oraxen:<id>} in the stack's item_model component. Nothing in the pack
 * says which material the item is built on, so the converter guessed from the filename
 * and fell back to paper, sticks and swords. A CustomCrops tomato on {@code APPLE} then
 * had no Geyser mapping under apple, and Bedrock players saw a plain apple.</p>
 *
 * <p>This follows Oraxen's own loader (ConfigsManager and ItemLoader): every {@code .yml}
 * under {@code items/}, a section with a boolean {@code template} key is a template, and
 * an item's material is its own {@code material}, else its {@code template}'s, else
 * PAPER. Keys are matched case-insensitively because older configs still say
 * {@code Pack:} and {@code Components:} until Oraxen migrates them.</p>
 */
final class OraxenItemBases {

    private static final String NAMESPACE = "oraxen";

    private OraxenItemBases() {
    }

    /**
     * @return base items keyed by item model id, such as
     *         {@code oraxen:tomato -> [minecraft:apple]}; empty when the folder is
     *         missing or unreadable
     */
    static Map<String, List<String>> read(File oraxenDataFolder) {
        File itemsFolder = new File(oraxenDataFolder, "items");
        if (!itemsFolder.isDirectory()) return Map.of();

        List<YamlConfiguration> configs = new ArrayList<>();
        try (Stream<Path> files = Files.walk(itemsFolder.toPath())) {
            for (Path file : files.filter(path -> path.toString().endsWith(".yml") && Files.isRegularFile(path))
                    .sorted().toList()) {
                YamlConfiguration config = new YamlConfiguration();
                try {
                    config.load(file.toFile());
                } catch (IOException | InvalidConfigurationException invalid) {
                    // Oraxen skips invalid files too, so they define no items.
                    continue;
                }
                configs.add(config);
            }
        } catch (IOException | UncheckedIOException unreadable) {
            return Map.of();
        }

        // Templates are global across files, as in Oraxen's ItemTemplate registry.
        Map<String, ConfigurationSection> templates = new HashMap<>();
        for (YamlConfiguration config : configs) {
            for (String key : config.getKeys(false)) {
                ConfigurationSection section = config.getConfigurationSection(key);
                String templateKey = section == null ? null : actualKey(section, "template");
                if (templateKey != null && section.isBoolean(templateKey)) templates.put(key, section);
            }
        }

        Map<String, List<String>> bases = new LinkedHashMap<>();
        for (YamlConfiguration config : configs) {
            for (String itemId : config.getKeys(false)) {
                ConfigurationSection section = config.getConfigurationSection(itemId);
                if (section == null || templates.containsKey(itemId)) continue;
                ConfigurationSection template = template(section, templates);
                String base = baseItem(section, template);
                if (base == null) continue;

                List<String> declared = List.of(base);
                bases.put(NAMESPACE + ":" + itemId, declared);
                // Pack.models entries get their own definitions, oraxen:<id>/<key>.
                ConfigurationSection models = child(child(section, "pack"), "models");
                if (models == null) models = child(child(template, "pack"), "models");
                if (models != null)
                    for (String modelKey : models.getKeys(false))
                        bases.put(NAMESPACE + ":" + itemId + "/" + modelKey, declared);
                // An explicit Components.item_model points the stack at another definition.
                String itemModel = string(child(section, "components"), "item_model");
                if (itemModel == null) itemModel = string(child(template, "components"), "item_model");
                NamespacedKey explicitModel = itemModel == null ? null : NamespacedKey.fromString(itemModel.trim());
                if (explicitModel != null) bases.putIfAbsent(explicitModel.toString(), declared);
            }
        }
        return bases;
    }

    private static ConfigurationSection template(ConfigurationSection section,
                                                 Map<String, ConfigurationSection> templates) {
        String key = actualKey(section, "template");
        return key != null && section.isString(key) ? templates.get(section.getString(key)) : null;
    }

    private static String baseItem(ConfigurationSection section, ConfigurationSection template) {
        Material material = material(string(section, "material"));
        if (material == null && template != null) material = material(string(template, "material"));
        if (material == null) material = Material.PAPER;
        // No isItem() check: Oraxen cannot build a stack of a block-only material, so such
        // an item gets no definition for this declaration to match.
        return material.isLegacy() ? null : material.getKey().toString();
    }

    private static Material material(String name) {
        return name == null || name.isBlank() ? null : Material.getMaterial(name.trim().toUpperCase(Locale.ROOT));
    }

    private static String string(ConfigurationSection section, String key) {
        String actual = section == null ? null : actualKey(section, key);
        return actual != null && section.isString(actual) ? section.getString(actual) : null;
    }

    private static ConfigurationSection child(ConfigurationSection section, String key) {
        String actual = section == null ? null : actualKey(section, key);
        return actual == null ? null : section.getConfigurationSection(actual);
    }

    private static String actualKey(ConfigurationSection section, String key) {
        if (section.contains(key)) return key;
        for (String existing : section.getKeys(false))
            if (existing.equalsIgnoreCase(key)) return existing;
        return null;
    }
}
