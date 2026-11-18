package com.magmaguy.resourcepackmanager.bedrock;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Gives Geyser the translations of the merged Java pack.
 *
 * <p>An item named with a translation key ({@code item_name: {translate: "item.pack.tinder"}},
 * MiniMessage {@code <lang:key>}) relies on the Java pack's {@code assets/<namespace>/lang}
 * files. Geyser translates Java text itself, from Mojang's language file plus
 * {@code <Geyser>/locales/overrides/<locale>.json}, and sends a key it does not know as literal
 * text, so Bedrock players read {@code item.pack.tinder} (GitHub ResourcePackManager#21, support
 * ticket "Converting issue"). A Bedrock {@code .lang} file cannot help: Geyser never asks the
 * Bedrock client to translate.
 *
 * <p>The overrides folder belongs to the admin. RSPM records every entry it adds, per locale,
 * with the value it wrote. It adds a key only where the file has none, so an admin's entry
 * always wins, and it later removes or updates only entries that still hold the value it
 * wrote. A file RSPM cannot parse is left alone.
 */
public final class GeyserLocaleOverrides {

    private static final Pattern LANG_FILE = Pattern.compile("assets/([a-z0-9_.-]+)/lang/([a-z0-9_]+)\\.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String DIRECTORY = "directory";
    private static final String LOCALES = "locales";

    private GeyserLocaleOverrides() {
    }

    /**
     * @param locales     locale files RSPM wrote, changed or removed
     * @param added       entries RSPM now owns across all locales
     * @param leftToAdmin pack entries not written because the file already had the key
     */
    public record Result(int locales, int added, int leftToAdmin) {
        public boolean changed() {
            return locales > 0;
        }
    }

    /**
     * The translations of every namespace in a merged Java pack, per locale. Takes the pack as
     * a folder or as its zip.
     */
    public static Map<String, Map<String, String>> readPack(File mergedJavaPack) throws IOException {
        Map<String, Map<String, String>> translations = new TreeMap<>();
        if (mergedJavaPack == null) return translations;
        // Sorted, so a key two namespaces define resolves the same way on every run.
        Map<String, String> files = new TreeMap<>();
        if (mergedJavaPack.isDirectory()) {
            Path root = mergedJavaPack.toPath();
            Path assets = root.resolve("assets");
            if (!Files.isDirectory(assets)) return translations;
            try (Stream<Path> walk = Files.walk(assets, 3)) {
                for (Path file : (Iterable<Path>) walk::iterator) {
                    String name = root.relativize(file).toString().replace('\\', '/');
                    if (LANG_FILE.matcher(name).matches() && Files.isRegularFile(file))
                        files.put(name, new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                }
            }
        } else if (mergedJavaPack.isFile()) {
            try (ZipFile zip = new ZipFile(mergedJavaPack)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory() || !LANG_FILE.matcher(entry.getName()).matches()) continue;
                    try (InputStream in = zip.getInputStream(entry)) {
                        files.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    }
                }
            }
        }
        for (Map.Entry<String, String> file : files.entrySet()) {
            Matcher name = LANG_FILE.matcher(file.getKey());
            if (!name.matches()) continue;
            Map<String, String> entries = flatStrings(file.getValue());
            if (entries == null || entries.isEmpty()) continue;
            translations.computeIfAbsent(name.group(2), locale -> new TreeMap<>()).putAll(entries);
        }
        return translations;
    }

    /**
     * The entries worth giving to Geyser. A pack entry that replaces one of Minecraft's own
     * texts with private-use glyphs is left out: Java packs do that to draw a custom screen
     * through their font ({@code container.crafting} becomes two glyphs), RSPM converts no
     * fonts, and a Bedrock player would read boxes where "Crafting" stood. A custom key keeps
     * its glyphs, since a box next to the text is still better than the raw key.
     *
     * @param vanillaKeys the keys of Geyser's {@code locales/en_us.json}, or {@code null} when
     *                    that file is not there yet; then no entry with such glyphs is exported
     */
    public static Map<String, Map<String, String>> exportable(Map<String, Map<String, String>> pack,
                                                              Set<String> vanillaKeys) {
        Map<String, Map<String, String>> out = new TreeMap<>();
        pack.forEach((locale, entries) -> {
            Map<String, String> kept = new TreeMap<>();
            entries.forEach((key, value) -> {
                boolean mayReplaceVanillaText = vanillaKeys == null || vanillaKeys.contains(key);
                if (mayReplaceVanillaText && hasPrivateUseGlyph(value)) return;
                kept.put(key, value);
            });
            if (!kept.isEmpty()) out.put(locale, kept);
        });
        return out;
    }

    /** The keys of a flat language file such as Geyser's {@code locales/en_us.json}, or null. */
    public static Set<String> keysOf(Path languageFile) {
        if (languageFile == null || !Files.isRegularFile(languageFile)) return null;
        try {
            Map<String, String> entries = flatStrings(new String(Files.readAllBytes(languageFile), StandardCharsets.UTF_8));
            return entries == null || entries.isEmpty() ? null : new TreeSet<>(entries.keySet());
        } catch (IOException unreadable) {
            return null;
        }
    }

    /**
     * Makes {@code overridesDirectory} hold {@code desired} next to whatever the admin keeps
     * there, and takes back what an earlier run added that is no longer wanted.
     *
     * @param overridesDirectory {@code <Geyser>/locales/overrides}, or {@code null} to only take
     *                           back what RSPM added (no Geyser here, or deployment switched off)
     * @param ownershipFile      RSPM's record of the entries it added
     * @param desired            entries per locale, from {@link #exportable}
     */
    public static Result reconcile(Path overridesDirectory, Path ownershipFile,
                                   Map<String, Map<String, String>> desired) throws IOException {
        Path target = overridesDirectory == null ? null : overridesDirectory.toAbsolutePath().normalize();
        Ownership owned = Ownership.read(ownershipFile);
        int touched = 0;
        int added = 0;
        int leftToAdmin = 0;

        // Geyser moved or deployment stopped: take the entries back from the old folder first.
        if (owned.directory != null && !owned.directory.equals(target)) {
            for (Map.Entry<String, Map<String, String>> locale : owned.locales.entrySet()) {
                Applied applied = apply(owned.directory.resolve(locale.getKey() + ".json"), locale.getValue(), Map.of());
                if (applied.changed) touched++;
            }
            owned = new Ownership(null, new TreeMap<>());
        }

        Map<String, Map<String, String>> nowOwned = new TreeMap<>();
        if (target != null) {
            Set<String> locales = new TreeSet<>(owned.locales.keySet());
            locales.addAll(desired.keySet());
            for (String locale : locales) {
                Applied applied = apply(target.resolve(locale + ".json"),
                        owned.locales.getOrDefault(locale, Map.of()), desired.getOrDefault(locale, Map.of()));
                if (applied.changed) touched++;
                added += applied.owned.size();
                leftToAdmin += applied.leftToAdmin;
                if (!applied.owned.isEmpty()) nowOwned.put(locale, applied.owned);
            }
        }
        new Ownership(nowOwned.isEmpty() ? null : target, nowOwned).write(ownershipFile);
        return new Result(touched, added, leftToAdmin);
    }

    private record Applied(Map<String, String> owned, int leftToAdmin, boolean changed) {
    }

    private static Applied apply(Path file, Map<String, String> previouslyOwned, Map<String, String> desired)
            throws IOException {
        boolean existed = Files.isRegularFile(file);
        JsonObject current = new JsonObject();
        if (existed) {
            try {
                current = JsonParser.parseString(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
                        .getAsJsonObject();
            } catch (RuntimeException notOurs) {
                // The admin's file, in a state RSPM cannot merge into. Leave it, and keep the
                // record so the entries are taken back once it parses again.
                BedrockLog.warn("[BedrockConverter] Left Geyser locale override " + file
                        + " alone because it is not a JSON object: " + notOurs.getMessage());
                return new Applied(new TreeMap<>(previouslyOwned), 0, false);
            }
        }
        JsonObject before = current.deepCopy();

        // An entry is still RSPM's only while it holds the value RSPM wrote.
        boolean tookBack = false;
        for (Map.Entry<String, String> entry : previouslyOwned.entrySet()) {
            if (entry.getValue().equals(stringOf(current.get(entry.getKey())))) {
                current.remove(entry.getKey());
                tookBack = true;
            }
        }
        Map<String, String> owned = new TreeMap<>();
        int leftToAdmin = 0;
        for (Map.Entry<String, String> entry : desired.entrySet()) {
            if (current.has(entry.getKey())) {
                leftToAdmin++;
                continue;
            }
            current.addProperty(entry.getKey(), entry.getValue());
            owned.put(entry.getKey(), entry.getValue());
        }

        if (current.equals(before)) return new Applied(owned, leftToAdmin, false);
        if (current.size() == 0 && tookBack) {
            // Nothing but RSPM's entries was in it.
            Files.deleteIfExists(file);
        } else {
            writeAtomically(file, GSON.toJson(current) + "\n");
        }
        return new Applied(owned, leftToAdmin, true);
    }

    private static void writeAtomically(Path file, String content) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path pending = file.resolveSibling("." + file.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.writeString(pending, content, StandardCharsets.UTF_8);
            try {
                Files.move(pending, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicMoveFailed) {
                Files.move(pending, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    /** What RSPM added, and where. */
    private record Ownership(Path directory, Map<String, Map<String, String>> locales) {

        static Ownership read(Path file) {
            Ownership nothing = new Ownership(null, new TreeMap<>());
            if (file == null || !Files.isRegularFile(file)) return nothing;
            try {
                JsonObject json = JsonParser.parseString(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
                        .getAsJsonObject();
                String directory = stringOf(json.get(DIRECTORY));
                if (directory == null || directory.isBlank() || !json.has(LOCALES)) return nothing;
                Map<String, Map<String, String>> locales = new TreeMap<>();
                for (Map.Entry<String, JsonElement> locale : json.getAsJsonObject(LOCALES).entrySet()) {
                    // The name becomes a file name in the recorded folder.
                    if (!locale.getKey().matches("[a-z0-9_]+")) continue;
                    Map<String, String> entries = flatStrings(locale.getValue().toString());
                    if (entries != null && !entries.isEmpty()) locales.put(locale.getKey(), entries);
                }
                Path recorded = Path.of(directory).toAbsolutePath().normalize();
                // RSPM only ever records <Geyser>/locales/overrides.
                if (recorded.getFileName() == null || !"overrides".equals(recorded.getFileName().toString())
                        || recorded.getParent() == null || recorded.getParent().getFileName() == null
                        || !"locales".equals(recorded.getParent().getFileName().toString())) return nothing;
                return new Ownership(recorded, locales);
            } catch (IOException | RuntimeException unreadable) {
                // Without a readable record RSPM owns nothing, so it removes nothing.
                return nothing;
            }
        }

        void write(Path file) throws IOException {
            if (file == null) return;
            if (directory == null || locales.isEmpty()) {
                Files.deleteIfExists(file);
                return;
            }
            JsonObject json = new JsonObject();
            json.addProperty(DIRECTORY, directory.toString());
            JsonObject byLocale = new JsonObject();
            locales.forEach((locale, entries) -> {
                JsonObject object = new JsonObject();
                entries.forEach(object::addProperty);
                byLocale.add(locale, object);
            });
            json.add(LOCALES, byLocale);
            String content = new Gson().toJson(json) + "\n";
            if (Files.isRegularFile(file)
                    && content.equals(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))) return;
            writeAtomically(file, content);
        }
    }

    /** A flat {@code key: "text"} JSON object as a map, without non-text values; null if it is not one. */
    private static Map<String, String> flatStrings(String json) {
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) return null;
            Map<String, String> entries = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : parsed.getAsJsonObject().entrySet()) {
                String value = stringOf(entry.getValue());
                if (value != null) entries.put(entry.getKey(), value);
            }
            return entries;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static String stringOf(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                ? element.getAsString() : null;
    }

    private static boolean hasPrivateUseGlyph(String text) {
        return text.codePoints().anyMatch(codePoint -> Character.getType(codePoint) == Character.PRIVATE_USE);
    }
}
