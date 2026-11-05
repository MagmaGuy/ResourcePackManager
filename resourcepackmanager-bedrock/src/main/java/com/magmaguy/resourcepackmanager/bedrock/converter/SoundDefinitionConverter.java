package com.magmaguy.resourcepackmanager.bedrock.converter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.magmaguy.resourcepackmanager.bedrock.BedrockLog;
import com.magmaguy.resourcepackmanager.bedrock.util.BedrockShortName;
import com.magmaguy.resourcepackmanager.mixer.engine.internal.Cancellation;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * Carries custom Java sounds into the Bedrock pack.
 *
 * <p>Java and Bedrock both play Ogg Vorbis, so the audio files are copied byte for byte. The work is
 * the definitions: every {@code assets/<namespace>/sounds.json} outside the {@code minecraft}
 * namespace becomes entries of {@code sounds/sound_definitions.json}, keyed by the full Java sound
 * event id ({@code oraxen:song.battle}). Geyser strips only {@code minecraft:} from a sound name and
 * forwards any sound it has no mapping for unchanged, so that id is the name a Bedrock client is asked
 * to play.</p>
 *
 * <p>Vanilla replacements ({@code minecraft} namespace) are left out: Geyser maps those to Bedrock's
 * own sound events, which a Java event key would not reach. Overlay directories are not read.</p>
 *
 * <p>Per sound, {@code stream}, {@code volume}, {@code pitch} and {@code weight} carry over; Java's
 * {@code preload} has no Bedrock equivalent. The largest {@code attenuation_distance} of an event's
 * sounds becomes the definition's {@code max_distance}. Java's sounds.json has no category (the server
 * chooses one per play and Geyser does not forward it), so each event gets {@code record} when any of
 * its sounds streams, which is how Java packs ship songs and puts them under Bedrock's Jukebox/Note
 * Blocks volume slider, and {@code neutral} otherwise, the category Bedrock's own custom-sound examples
 * use.</p>
 *
 * <p>{@code type: "event"} references are inlined the way the Java client resolves them: the referenced
 * event's sounds join the list with their own weights (Java weighs a reference by the referenced
 * event's total weight), with volume and pitch multiplied by the reference's and {@code stream} set
 * when either side streams. A file that sounds.json names but the pack does not contain is skipped with
 * a warning; it never fails the conversion.</p>
 */
public final class SoundDefinitionConverter {
    public static final String DEFINITIONS_PATH = "sounds/sound_definitions.json";
    /** The format_version of the vanilla Bedrock resource pack's sound_definitions.json. */
    static final String FORMAT_VERSION = "1.20.20";
    static final String STREAMED_CATEGORY = "record";
    static final String DEFAULT_CATEGORY = "neutral";

    private static final String VANILLA_NAMESPACE = "minecraft";
    private static final int GEYSER_PATH_WARNING_LENGTH = 80;
    private static final int MAX_REFERENCE_DEPTH = 16;
    private static final int WARNING_SAMPLE = 5;
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9/._-]+");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Files and bytes one source pack contributed. */
    public record SourceTotals(int files, long bytes) {
    }

    /**
     * Outcome of one conversion. {@code bySource} is keyed by source pack name when the caller knows
     * which pack supplied each file, and by {@code assets/<namespace>} otherwise.
     */
    public record Result(int events, int files, long bytes, Map<String, SourceTotals> bySource) {
        public static final Result EMPTY = new Result(0, 0, 0, Map.of());

        public Result {
            bySource = bySource == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(bySource));
        }
    }

    private record FileSound(String namespace, String path, float volume, float pitch, int weight,
                             boolean stream, int attenuation) {
    }

    private SoundDefinitionConverter() {
    }

    /**
     * @param javaPack     merged Java pack root whose {@code assets/} holds the sounds
     * @param bedrockPack  Bedrock pack staging root
     * @param fileSources  source pack name per Java path ({@code assets/<ns>/sounds/<path>.ogg}); may be empty
     */
    public static Result convert(File javaPack, File bedrockPack, Map<String, String> fileSources,
                                 BooleanSupplier cancellationRequested) throws IOException {
        File assets = new File(javaPack, "assets");
        File[] namespaces = assets.isDirectory() ? assets.listFiles(File::isDirectory) : null;
        if (namespaces == null || namespaces.length == 0) return Result.EMPTY;
        Arrays.sort(namespaces, Comparator.comparing(File::getName));

        // Every namespace's events are kept so references can reach them; only events outside
        // the minecraft namespace get a definition.
        Map<String, JsonObject> events = new LinkedHashMap<>();
        List<String> convertible = new ArrayList<>();
        for (File namespace : namespaces) {
            File soundsJson = new File(namespace, "sounds.json");
            if (!soundsJson.isFile()) continue;
            JsonObject root = readObject(soundsJson);
            if (root == null) {
                BedrockLog.warn("[BedrockConverter] Could not read assets/" + namespace.getName()
                        + "/sounds.json; its sounds are left out of the Bedrock pack.");
                continue;
            }
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                String id = namespace.getName() + ":" + entry.getKey();
                events.put(id, entry.getValue().getAsJsonObject());
                if (!namespace.getName().equals(VANILLA_NAMESPACE)) convertible.add(id);
            }
        }
        if (convertible.isEmpty()) return Result.EMPTY;

        Path packRoot = bedrockPack.toPath();
        Map<String, String> copied = new HashMap<>(); // Java file path -> Bedrock sound name
        Map<String, SourceTotals> bySource = new LinkedHashMap<>();
        Set<String> missingFiles = new LinkedHashSet<>();
        Set<String> unresolvedEvents = new LinkedHashSet<>();
        Set<String> invalidNames = new LinkedHashSet<>();
        int files = 0;
        long bytes = 0;
        JsonObject definitions = new JsonObject();

        for (String id : convertible) {
            if (Cancellation.isCancelled(cancellationRequested)) throw new CancellationException();
            List<FileSound> sounds = new ArrayList<>();
            Set<String> visiting = new HashSet<>();
            visiting.add(id);
            flatten(events.get(id), events, 1f, 1f, false, sounds, visiting, unresolvedEvents, invalidNames, 0);

            JsonArray bedrockSounds = new JsonArray();
            boolean streamed = false;
            int attenuation = 0;
            for (FileSound sound : sounds) {
                String javaPath = "assets/" + sound.namespace() + "/sounds/" + sound.path() + ".ogg";
                String bedrockName = copied.get(javaPath);
                if (bedrockName == null) {
                    Path source = javaPack.toPath().resolve(javaPath).normalize();
                    if (!source.startsWith(javaPack.toPath().normalize()) || !Files.isRegularFile(source)) {
                        missingFiles.add(sound.namespace() + ":" + sound.path());
                        continue;
                    }
                    String target = bedrockTarget(sound.namespace(), sound.path());
                    Path destination = packRoot.resolve(target);
                    Files.createDirectories(destination.getParent());
                    Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
                    long size = Files.size(destination);
                    String owner = fileSources == null ? null : fileSources.get(javaPath);
                    if (owner == null) owner = "assets/" + sound.namespace();
                    SourceTotals totals = bySource.getOrDefault(owner, new SourceTotals(0, 0));
                    bySource.put(owner, new SourceTotals(totals.files() + 1, totals.bytes() + size));
                    files++;
                    bytes += size;
                    bedrockName = target.substring(0, target.length() - ".ogg".length());
                    copied.put(javaPath, bedrockName);
                }
                JsonObject entry = new JsonObject();
                entry.addProperty("name", bedrockName);
                if (sound.stream()) {
                    entry.addProperty("stream", true);
                    streamed = true;
                }
                if (sound.volume() != 1f) entry.addProperty("volume", sound.volume());
                if (sound.pitch() != 1f) entry.addProperty("pitch", sound.pitch());
                if (sound.weight() != 1) entry.addProperty("weight", sound.weight());
                attenuation = Math.max(attenuation, sound.attenuation());
                bedrockSounds.add(entry);
            }
            if (bedrockSounds.isEmpty()) continue;

            JsonObject definition = new JsonObject();
            definition.addProperty("category", streamed ? STREAMED_CATEGORY : DEFAULT_CATEGORY);
            // Bedrock ignores an integer max_distance; it must be written as a float.
            if (attenuation > 0) definition.addProperty("max_distance", (double) attenuation);
            definition.add("sounds", bedrockSounds);
            definitions.add(id, definition);
        }

        warnSkipped(missingFiles, "sound file(s) named in sounds.json but missing from the merged pack "
                + "(Bedrock cannot borrow Java's built-in sounds)");
        warnSkipped(unresolvedEvents, "sound event reference(s) that no sounds.json in the merged pack defines");
        warnSkipped(invalidNames, "sound name(s) that are not valid Java resource locations");
        if (definitions.isEmpty()) return new Result(0, files, bytes, bySource);

        JsonObject root = new JsonObject();
        root.addProperty("format_version", FORMAT_VERSION);
        root.add("sound_definitions", definitions);
        Path output = packRoot.resolve(DEFINITIONS_PATH);
        Files.createDirectories(output.getParent());
        try (Writer writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        }
        return new Result(definitions.size(), files, bytes, bySource);
    }

    private static void flatten(JsonObject event, Map<String, JsonObject> events, float volume, float pitch,
                                boolean stream, List<FileSound> out, Set<String> visiting,
                                Set<String> unresolved, Set<String> invalid, int depth) {
        if (event == null || !event.has("sounds") || !event.get("sounds").isJsonArray()) return;
        for (JsonElement element : event.getAsJsonArray("sounds")) {
            String name;
            String type = "file";
            float entryVolume = 1f;
            float entryPitch = 1f;
            int weight = 1;
            boolean entryStream = false;
            int attenuation = 0;
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                name = element.getAsString();
            } else if (element.isJsonObject()) {
                JsonObject object = element.getAsJsonObject();
                name = string(object, "name");
                if (object.has("type")) type = string(object, "type");
                entryVolume = number(object, "volume", 1f);
                entryPitch = number(object, "pitch", 1f);
                weight = Math.max(0, Math.round(number(object, "weight", 1f)));
                entryStream = bool(object, "stream");
                attenuation = Math.max(0, Math.round(number(object, "attenuation_distance", 0f)));
            } else {
                continue;
            }
            if (name == null || name.isBlank()) continue;
            String[] id = splitId(name);
            if ("event".equals(type)) {
                String eventId = id[0] + ":" + id[1];
                JsonObject referenced = events.get(eventId);
                if (referenced == null) {
                    unresolved.add(eventId);
                } else if (depth < MAX_REFERENCE_DEPTH && visiting.add(eventId)) {
                    flatten(referenced, events, volume * entryVolume, pitch * entryPitch, stream || entryStream,
                            out, visiting, unresolved, invalid, depth + 1);
                    visiting.remove(eventId);
                } else {
                    BedrockLog.debug("[BedrockConverter] Skipping circular or too deep sound event reference "
                            + eventId + ".");
                }
                continue;
            }
            if (!"file".equals(type) || weight == 0) continue;
            if (!isValidId(id[0], id[1])) {
                invalid.add(name);
                continue;
            }
            out.add(new FileSound(id[0], id[1], volume * entryVolume, pitch * entryPitch, weight,
                    stream || entryStream, attenuation));
        }
    }

    /** Readable sounds/<ns>/<path>.ogg, or a short hashed name where that reaches Geyser's 80-character warning. */
    private static String bedrockTarget(String namespace, String path) {
        String readable = "sounds/" + namespace + "/" + path + ".ogg";
        if (readable.length() < GEYSER_PATH_WARNING_LENGTH) return readable;
        return "sounds/" + BedrockShortName.BEDROCK_NAMESPACE + "/"
                + BedrockShortName.forModel(namespace + ":" + path) + ".ogg";
    }

    private static String[] splitId(String name) {
        int colon = name.indexOf(':');
        if (colon < 0) return new String[]{VANILLA_NAMESPACE, name};
        return new String[]{name.substring(0, colon), name.substring(colon + 1)};
    }

    /** Java's resource location rules, plus no empty, "." or ".." path segments. */
    private static boolean isValidId(String namespace, String path) {
        if (!NAMESPACE.matcher(namespace).matches() || !PATH.matcher(path).matches()) return false;
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals("..") || segment.equals(".")) return false;
        }
        return true;
    }

    private static void warnSkipped(Set<String> skipped, String what) {
        if (skipped.isEmpty()) return;
        List<String> sample = new ArrayList<>(skipped).subList(0, Math.min(WARNING_SAMPLE, skipped.size()));
        BedrockLog.warn("[BedrockConverter] Skipped " + skipped.size() + " " + what + ": "
                + String.join(", ", sample)
                + (skipped.size() > WARNING_SAMPLE ? " (+" + (skipped.size() - WARNING_SAMPLE) + " more)" : "")
                + ".");
        for (String entry : skipped) BedrockLog.debug("[BedrockConverter] Skipped sound " + entry);
    }

    private static JsonObject readObject(File file) {
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static float number(JsonObject object, String key, float fallback) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) return fallback;
        try {
            return value.getAsFloat();
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean bool(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsBoolean();
    }

    /** 1024-based size for log lines. */
    public static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.2f MiB", bytes / (1024.0 * 1024));
        return String.format(Locale.ROOT, "%.2f GiB", bytes / (1024.0 * 1024 * 1024));
    }
}
