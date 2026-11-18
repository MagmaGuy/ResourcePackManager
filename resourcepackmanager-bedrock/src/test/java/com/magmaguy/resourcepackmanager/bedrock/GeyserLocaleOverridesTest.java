package com.magmaguy.resourcepackmanager.bedrock;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeyserLocaleOverridesTest {
    private static final Map<String, Map<String, String>> PACK = Map.of(
            "en_us", Map.of("item.kit.tinder", "Tinder", "item.kit.copper_dolabra", "Copper Dolabra"),
            "fr_fr", Map.of("item.kit.tinder", "Amadou"));

    @TempDir
    Path directory;

    private Path overrides() {
        return directory.resolve("Geyser-Spigot/locales/overrides");
    }

    private Path ownership() {
        return directory.resolve("ResourcePackManager/.rspm-geyser-locale-overrides.json");
    }

    private GeyserLocaleOverrides.Result reconcile(Map<String, Map<String, String>> desired) throws IOException {
        return GeyserLocaleOverrides.reconcile(overrides(), ownership(), desired);
    }

    private JsonObject file(String locale) throws IOException {
        return JsonParser.parseString(Files.readString(overrides().resolve(locale + ".json"))).getAsJsonObject();
    }

    private void adminWrites(String locale, String json) throws IOException {
        Files.createDirectories(overrides());
        Files.writeString(overrides().resolve(locale + ".json"), json, StandardCharsets.UTF_8);
    }

    @Test
    void thePackTranslationsOfEveryNamespaceAreReadFromAZipOrAFolder() throws IOException {
        Path zip = directory.resolve("ResourcePackManager_RSP.zip");
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream archive = new ZipOutputStream(out)) {
            entry(archive, "assets/minecraft/lang/en_us.json",
                    "{\"item.kit.tinder\": \"Tinder\", \"not.text\": 5, \"block.minecraft.stone\": \"Rock\"}");
            entry(archive, "assets/kit/lang/en_us.json", "{\"item.kit.copper_dolabra\": \"Copper Dolabra\"}");
            entry(archive, "assets/kit/lang/fr_fr.json", "{\"item.kit.tinder\": \"Amadou\"}");
            entry(archive, "assets/kit/lang/broken.json", "{\"item.kit.tinder\": ");
            entry(archive, "assets/kit/lang/sub/en_us.json", "{\"nested\": \"no\"}");
            entry(archive, "assets/kit/items/lang.json", "{\"items\": \"no\"}");
        }
        Map<String, Map<String, String>> expected = Map.of(
                "en_us", Map.of("item.kit.tinder", "Tinder", "block.minecraft.stone", "Rock",
                        "item.kit.copper_dolabra", "Copper Dolabra"),
                "fr_fr", Map.of("item.kit.tinder", "Amadou"));

        assertEquals(expected, GeyserLocaleOverrides.readPack(zip.toFile()));

        Path folder = directory.resolve("merged");
        write(folder.resolve("assets/minecraft/lang/en_us.json"),
                "{\"item.kit.tinder\": \"Tinder\", \"block.minecraft.stone\": \"Rock\"}");
        write(folder.resolve("assets/kit/lang/en_us.json"), "{\"item.kit.copper_dolabra\": \"Copper Dolabra\"}");
        write(folder.resolve("assets/kit/lang/fr_fr.json"), "{\"item.kit.tinder\": \"Amadou\"}");
        assertEquals(expected, GeyserLocaleOverrides.readPack(folder.toFile()));
        assertEquals(Map.of(), GeyserLocaleOverrides.readPack(directory.resolve("absent.zip").toFile()));
    }

    @Test
    void glyphTextThatReplacesMinecraftsOwnTextStaysOutOfGeyser() throws IOException {
        Map<String, Map<String, String>> pack = Map.of("en_us", Map.of(
                "container.crafting", "",
                "block.minecraft.stone", "Rock",
                "desc.kit.mining_speed", " %s",
                "item.kit.tinder", "Tinder"));
        Path vanilla = directory.resolve("en_us.json");
        Files.writeString(vanilla, "{\"container.crafting\": \"Crafting\", \"block.minecraft.stone\": \"Stone\"}");

        assertEquals(Map.of("en_us", Map.of("block.minecraft.stone", "Rock",
                        "desc.kit.mining_speed", " %s", "item.kit.tinder", "Tinder")),
                GeyserLocaleOverrides.exportable(pack, GeyserLocaleOverrides.keysOf(vanilla)));

        // Before Geyser has stored Mojang's language file, any glyph text could be such a replacement.
        assertNull(GeyserLocaleOverrides.keysOf(directory.resolve("not_downloaded_yet.json")));
        assertEquals(Map.of("en_us", Map.of("block.minecraft.stone", "Rock", "item.kit.tinder", "Tinder")),
                GeyserLocaleOverrides.exportable(pack, null));
    }

    @Test
    void entriesAreAddedOnceAndAnUnchangedPackRewritesNothing() throws IOException {
        GeyserLocaleOverrides.Result first = reconcile(PACK);

        assertEquals(2, first.locales());
        assertEquals(3, first.added());
        assertEquals("Copper Dolabra", file("en_us").get("item.kit.copper_dolabra").getAsString());
        assertEquals("Amadou", file("fr_fr").get("item.kit.tinder").getAsString());

        long written = Files.getLastModifiedTime(overrides().resolve("en_us.json")).toMillis();
        long recorded = Files.getLastModifiedTime(ownership()).toMillis();
        GeyserLocaleOverrides.Result second = reconcile(PACK);

        assertFalse(second.changed());
        assertEquals(3, second.added());
        assertEquals(written, Files.getLastModifiedTime(overrides().resolve("en_us.json")).toMillis());
        assertEquals(recorded, Files.getLastModifiedTime(ownership()).toMillis());
    }

    @Test
    void anAdminEntryWinsAndIsNeverRemoved() throws IOException {
        adminWrites("en_us", "{\"item.kit.tinder\": \"Firestarter\", \"my.own.key\": \"Mine\"}");

        GeyserLocaleOverrides.Result result = reconcile(PACK);

        assertEquals(1, result.leftToAdmin());
        assertEquals("Firestarter", file("en_us").get("item.kit.tinder").getAsString());
        assertEquals("Mine", file("en_us").get("my.own.key").getAsString());
        assertEquals("Copper Dolabra", file("en_us").get("item.kit.copper_dolabra").getAsString());

        // The pack loses all its text: only what RSPM added goes.
        reconcile(Map.of());

        assertEquals(Set.of("item.kit.tinder", "my.own.key"), file("en_us").keySet());
        assertFalse(Files.exists(overrides().resolve("fr_fr.json")), "a file with only RSPM's entries is removed");
        assertFalse(Files.exists(ownership()));
    }

    @Test
    void aChangedPackUpdatesItsEntriesButNotOnesTheAdminEdited() throws IOException {
        reconcile(PACK);
        JsonObject edited = file("en_us");
        edited.addProperty("item.kit.tinder", "Admin's Tinder");
        adminWrites("en_us", edited.toString());

        reconcile(Map.of("en_us", Map.of("item.kit.tinder", "Tinderbox", "item.kit.copper_dolabra", "Dolabra")));

        assertEquals("Admin's Tinder", file("en_us").get("item.kit.tinder").getAsString());
        assertEquals("Dolabra", file("en_us").get("item.kit.copper_dolabra").getAsString());
        assertFalse(Files.exists(overrides().resolve("fr_fr.json")));

        // The edited entry is the admin's from now on.
        reconcile(Map.of());
        assertEquals(Set.of("item.kit.tinder"), file("en_us").keySet());
    }

    @Test
    void withoutATargetOrAfterGeyserMovedTheOldFolderIsCleaned() throws IOException {
        reconcile(PACK);
        Path moved = directory.resolve("Geyser-Other/locales/overrides");

        GeyserLocaleOverrides.reconcile(moved, ownership(), PACK);

        assertFalse(Files.exists(overrides().resolve("en_us.json")));
        assertTrue(Files.exists(moved.resolve("en_us.json")));

        GeyserLocaleOverrides.Result withdrawn = GeyserLocaleOverrides.reconcile(null, ownership(), PACK);

        assertTrue(withdrawn.changed());
        assertFalse(Files.exists(moved.resolve("en_us.json")));
        assertFalse(Files.exists(ownership()));
    }

    @Test
    void aFileThatIsNotAJsonObjectAndAnEmptyAdminFileAreLeftAlone() throws IOException {
        adminWrites("en_us", "this is not json {");
        adminWrites("fr_fr", "{}");

        GeyserLocaleOverrides.Result result = reconcile(Map.of("en_us", Map.of("item.kit.tinder", "Tinder")));

        assertFalse(result.changed());
        assertEquals("this is not json {", Files.readString(overrides().resolve("en_us.json")));
        reconcile(Map.of());
        assertEquals("{}", Files.readString(overrides().resolve("fr_fr.json")));
    }

    @Test
    void aRecordThatPointsOutsideAGeyserOverridesFolderOwnsNothing() throws IOException {
        Path elsewhere = Files.createDirectories(directory.resolve("elsewhere"));
        Files.writeString(elsewhere.resolve("en_us.json"), "{\"item.kit.tinder\": \"Tinder\"}");
        Files.createDirectories(ownership().getParent());
        Files.writeString(ownership(), "{\"directory\": " + new com.google.gson.Gson().toJson(elsewhere.toString())
                + ", \"locales\": {\"en_us\": {\"item.kit.tinder\": \"Tinder\"}}}");

        GeyserLocaleOverrides.reconcile(null, ownership(), Map.of());

        assertEquals("{\"item.kit.tinder\": \"Tinder\"}", Files.readString(elsewhere.resolve("en_us.json")));
    }

    private static void entry(ZipOutputStream archive, String name, String content) throws IOException {
        archive.putNextEntry(new ZipEntry(name));
        archive.write(content.getBytes(StandardCharsets.UTF_8));
        archive.closeEntry();
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
