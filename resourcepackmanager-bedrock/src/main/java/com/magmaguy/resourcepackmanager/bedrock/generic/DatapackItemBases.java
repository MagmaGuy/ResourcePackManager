package com.magmaguy.resourcepackmanager.bedrock.generic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.magmaguy.resourcepackmanager.bedrock.BedrockLog;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The base items a server's data packs put custom item models on.
 *
 * <p>A resource pack does not say which Java item carries {@code matcha:tinder}; the data pack
 * that creates the item does: its recipe result is {@code minecraft:flint_and_steel} with
 * {@code minecraft:item_model: "matcha:tinder"}. Geyser looks a custom item up under the base
 * item of the stack it is given, so a model mapped under guessed base items (paper, sticks,
 * swords) stays vanilla on Bedrock. On one server 51 of 366 data pack item models had their real
 * base item among the guesses (support ticket "Converting issue", 2026-11-18).
 *
 * <p>The scan does not depend on a file's type. In JSON it takes every object that names an item
 * ({@code id}, {@code item}, a loot entry's {@code name}, a predicate's {@code items}) together
 * with {@code components} holding {@code minecraft:item_model}: recipe results, loot table
 * entries and their {@code set_components} functions, villager trades, advancement icons and
 * item predicates. In functions it takes {@code item[item_model=...]} arguments and item
 * compounds written as SNBT.
 */
public final class DatapackItemBases {

    private static final String ITEM_MODEL = "item_model";
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+(?::[a-z0-9_./-]+)?");
    private static final Pattern VANILLA_ITEM = Pattern.compile("minecraft:[a-z0-9_]+");
    private static final Pattern MODEL_VALUE = Pattern.compile("^[\"']?\\s*[=:]\\s*[\"']?([a-z0-9_.:/-]+)");
    private static final Pattern SNBT_ID = Pattern.compile("(?<![a-z0-9_])[\"']?id[\"']?\\s*:\\s*[\"']?([a-z0-9_.:-]+)");
    /** Folders that hold no item stacks and make up most of a worldgen pack's files. */
    private static final Set<String> SKIPPED_REGISTRIES = Set.of("worldgen", "structure", "structures", "tags");

    private DatapackItemBases() {
    }

    /**
     * @param basesByModel base items ({@code minecraft:<item>}) per item model id, both sorted
     * @param packsRead    data packs that named at least one item model
     */
    public record Result(Map<String, List<String>> basesByModel, int packsRead) {
        public static final Result EMPTY = new Result(Map.of(), 0);
    }

    /**
     * @param datapacks data pack folders and zip files, as they sit in a world's
     *                  {@code datapacks} folder
     */
    public static Result read(Collection<File> datapacks, BooleanSupplier cancelled) {
        Map<String, Set<String>> pairs = new TreeMap<>();
        int packsRead = 0;
        List<File> ordered = new ArrayList<>(datapacks);
        ordered.sort(Comparator.comparing(File::getName));
        for (File datapack : ordered) {
            if (cancelled.getAsBoolean()) return Result.EMPTY;
            int before = pairCount(pairs);
            try {
                if (datapack.isDirectory()) readFolder(datapack.toPath(), pairs, cancelled);
                else if (datapack.isFile() && datapack.getName().toLowerCase(Locale.ROOT).endsWith(".zip"))
                    readZip(datapack, pairs, cancelled);
            } catch (IOException | RuntimeException unreadable) {
                // An unreadable pack only costs its items their real base item.
                BedrockLog.warn("[BedrockConverter] Could not read the item base items of data pack "
                        + datapack.getName() + ": " + unreadable.getMessage());
            }
            if (pairCount(pairs) > before) packsRead++;
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        pairs.forEach((model, bases) -> result.put(model, List.copyOf(bases)));
        return new Result(java.util.Collections.unmodifiableMap(result), packsRead);
    }

    private static int pairCount(Map<String, Set<String>> pairs) {
        int count = 0;
        for (Set<String> bases : pairs.values()) count += bases.size();
        return count;
    }

    private static void readFolder(Path root, Map<String, Set<String>> pairs, BooleanSupplier cancelled)
            throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (cancelled.getAsBoolean()) return;
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (!isDataFile(relative) || !Files.isRegularFile(file)) continue;
                readFile(relative, new String(Files.readAllBytes(file), StandardCharsets.UTF_8), pairs);
            }
        }
    }

    private static void readZip(File zip, Map<String, Set<String>> pairs, BooleanSupplier cancelled)
            throws IOException {
        try (ZipFile archive = new ZipFile(zip)) {
            Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                if (cancelled.getAsBoolean()) return;
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().replace('\\', '/');
                if (entry.isDirectory() || !isDataFile(name)) continue;
                try (InputStream in = archive.getInputStream(entry)) {
                    readFile(name, new String(in.readAllBytes(), StandardCharsets.UTF_8), pairs);
                }
            }
        }
    }

    /** {@code data/<namespace>/<registry>/...} at the pack root or inside one overlay folder. */
    private static boolean isDataFile(String path) {
        if (!path.endsWith(".json") && !path.endsWith(".mcfunction")) return false;
        String[] segments = path.split("/");
        int data = segments[0].equals("data") ? 0 : segments.length > 1 && segments[1].equals("data") ? 1 : -1;
        if (data < 0 || segments.length < data + 4) return false;
        return !SKIPPED_REGISTRIES.contains(segments[data + 2]);
    }

    private static void readFile(String name, String text, Map<String, Set<String>> pairs) {
        if (!text.contains(ITEM_MODEL)) return;
        if (name.endsWith(".mcfunction")) {
            readFunction(text, pairs);
            return;
        }
        try {
            visit(JsonParser.parseString(text), List.of(), pairs);
        } catch (RuntimeException malformed) {
            // The server skips a file it cannot parse as well.
        }
    }

    // -------------------------------------------------------------------------
    // JSON
    // -------------------------------------------------------------------------

    private static void visit(JsonElement node, List<String> inheritedItems, Map<String, Set<String>> pairs) {
        if (node.isJsonArray()) {
            for (JsonElement child : node.getAsJsonArray()) visit(child, inheritedItems, pairs);
            return;
        }
        if (!node.isJsonObject()) return;
        JsonObject object = node.getAsJsonObject();
        List<String> own = itemsOf(object);
        List<String> items = own.isEmpty() ? inheritedItems : own;

        String model = modelOf(object.get("components"));
        if (model != null) for (String item : items) add(pairs, item, model);

        // Functions of a loot pool or table apply to every entry beneath them.
        if (object.has("functions") && (object.has("entries") || object.has("pools"))) {
            List<String> models = new ArrayList<>();
            for (JsonElement function : arrayOf(object.get("functions"))) {
                if (!function.isJsonObject()) continue;
                String functionModel = modelOf(function.getAsJsonObject().get("components"));
                if (functionModel != null) models.add(functionModel);
            }
            if (!models.isEmpty()) {
                List<String> entryItems = new ArrayList<>();
                collectEntryItems(object, entryItems);
                for (String item : entryItems) for (String functionModel : models) add(pairs, item, functionModel);
            }
        }

        for (Map.Entry<String, JsonElement> child : object.entrySet()) visit(child.getValue(), items, pairs);
    }

    /** The item ids an object names for itself: a stack, a loot entry or an item predicate. */
    private static List<String> itemsOf(JsonObject object) {
        String id = stringOf(object.get("id"));
        if (id != null) return List.of(id);
        String item = stringOf(object.get("item"));
        if (item != null) return List.of(item);
        String type = stringOf(object.get("type"));
        if (type != null && (type.equals("item") || type.equals("minecraft:item"))) {
            String name = stringOf(object.get("name"));
            if (name != null) return List.of(name);
        }
        JsonElement items = object.get("items");
        if (items == null) return List.of();
        if (items.isJsonArray()) {
            List<String> list = new ArrayList<>();
            for (JsonElement element : items.getAsJsonArray()) {
                String value = stringOf(element);
                if (value != null) list.add(value);
            }
            return list;
        }
        String single = stringOf(items);
        return single == null ? List.of() : List.of(single);
    }

    private static void collectEntryItems(JsonElement node, List<String> out) {
        if (node.isJsonArray()) {
            for (JsonElement child : node.getAsJsonArray()) collectEntryItems(child, out);
        } else if (node.isJsonObject()) {
            JsonObject object = node.getAsJsonObject();
            String type = stringOf(object.get("type"));
            String name = stringOf(object.get("name"));
            if (name != null && type != null && (type.equals("item") || type.equals("minecraft:item"))) out.add(name);
            for (Map.Entry<String, JsonElement> child : object.entrySet()) collectEntryItems(child.getValue(), out);
        }
    }

    private static String modelOf(JsonElement components) {
        if (components == null || !components.isJsonObject()) return null;
        JsonObject object = components.getAsJsonObject();
        String model = stringOf(object.get("minecraft:item_model"));
        return model != null ? model : stringOf(object.get(ITEM_MODEL));
    }

    private static JsonArray arrayOf(JsonElement element) {
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    private static String stringOf(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                ? element.getAsString() : null;
    }

    // -------------------------------------------------------------------------
    // Functions
    // -------------------------------------------------------------------------

    private static void readFunction(String text, Map<String, Set<String>> pairs) {
        for (String line : text.split("\n")) {
            int at = line.indexOf(ITEM_MODEL);
            while (at >= 0) {
                Matcher value = MODEL_VALUE.matcher(line.substring(at + ITEM_MODEL.length()));
                if (value.find()) {
                    String item = functionItem(line, at);
                    if (item != null) add(pairs, item, value.group(1));
                }
                at = line.indexOf(ITEM_MODEL, at + ITEM_MODEL.length());
            }
        }
    }

    /**
     * The item an {@code item_model} at {@code at} belongs to: the id in front of the
     * {@code [components]} argument around it, or the {@code id} of the SNBT item compound
     * whose {@code components} hold it.
     */
    private static String functionItem(String line, int at) {
        int opener = enclosingOpener(line, at);
        if (opener < 0) return null;
        if (line.charAt(opener) == '[') {
            int start = opener;
            while (start > 0 && isIdChar(line.charAt(start - 1))) start--;
            if (start == opener) return null;
            // "@s[...]" is a selector and "#tag[...]" a tag, not an item.
            if (start > 0 && (line.charAt(start - 1) == '@' || line.charAt(start - 1) == '#')) return null;
            return line.substring(start, opener);
        }
        // {id:"minecraft:stick",components:{"minecraft:item_model":"a:b"}}
        int compound = enclosingOpener(line, opener);
        if (compound < 0 || line.charAt(compound) != '{') return null;
        // Blank the components compound first, so an id inside it is not taken for the item's.
        String head = line.substring(compound, opener);
        Matcher id = SNBT_ID.matcher(head + line.substring(Math.min(line.length(), closing(line, opener) + 1),
                Math.min(line.length(), closing(line, compound) + 1)));
        return id.find() ? id.group(1) : null;
    }

    /** Index of the unmatched {@code [} or <code>{</code> left of {@code from}, or -1. */
    private static int enclosingOpener(String line, int from) {
        int depth = 0;
        for (int i = from - 1; i >= 0; i--) {
            char c = line.charAt(i);
            if (c == ']' || c == '}') depth++;
            else if (c == '[' || c == '{') {
                if (depth == 0) return i;
                depth--;
            }
        }
        return -1;
    }

    /** Index of the bracket that closes the one at {@code opener}, or the line's end. */
    private static int closing(String line, int opener) {
        int depth = 0;
        for (int i = opener; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '[' || c == '{') depth++;
            else if (c == ']' || c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return line.length() - 1;
    }

    private static boolean isIdChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == ':' || c == '-';
    }

    // -------------------------------------------------------------------------

    private static void add(Map<String, Set<String>> pairs, String item, String model) {
        String base = namespaced(item);
        String modelId = namespaced(model);
        // Only a vanilla item can be a Geyser base item; "#tag" and modded ids are not one.
        if (base == null || modelId == null || !VANILLA_ITEM.matcher(base).matches()) return;
        pairs.computeIfAbsent(modelId, key -> new TreeSet<>()).add(base);
    }

    private static String namespaced(String id) {
        if (id == null || !ID.matcher(id).matches()) return null;
        return id.indexOf(':') >= 0 ? id : "minecraft:" + id;
    }
}
