package com.magmaguy.resourcepackmanager.bedrock.generic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DatapackItemBasesTest {
    @TempDir
    Path directory;

    private Path file(String datapack, String path, String content) throws IOException {
        Path file = directory.resolve(datapack).resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private Map<String, List<String>> read(String... datapacks) {
        List<File> files = new java.util.ArrayList<>();
        for (String datapack : datapacks) files.add(directory.resolve(datapack).toFile());
        return DatapackItemBases.read(files, () -> false).basesByModel();
    }

    @Test
    void aRecipeResultNamesTheBaseItemOfItsModel() throws IOException {
        file("pack", "data/pack/recipe/tinder.json", """
                {"type": "minecraft:crafting_shapeless",
                 "ingredients": ["minecraft:stick", ["minecraft:fern", "minecraft:bush"]],
                 "result": {"id": "minecraft:flint_and_steel", "components": {
                   "minecraft:max_damage": 1, "minecraft:item_model": "pack:tinder"}}}""");
        file("pack", "data/pack/recipe/plain.json", """
                {"type": "minecraft:crafting_shapeless", "ingredients": ["minecraft:stick"],
                 "result": {"id": "minecraft:paper"}}""");

        assertEquals(Map.of("pack:tinder", List.of("minecraft:flint_and_steel")), read("pack"));
    }

    @Test
    void lootTablesNameItInAnEntryFunctionOrAPoolFunction() throws IOException {
        file("pack", "data/pack/loot_table/food.json", """
                {"pools": [
                  {"rolls": 1, "entries": [
                    {"type": "minecraft:item", "name": "minecraft:poisonous_potato", "functions": [
                      {"function": "minecraft:set_components", "components": {"minecraft:item_model": "pack:pickle"}}]}]},
                  {"rolls": 1,
                   "functions": [{"function": "minecraft:set_components", "components": {"item_model": "pack:fish"}}],
                   "entries": [
                    {"type": "minecraft:item", "name": "minecraft:cod"},
                    {"type": "minecraft:alternatives", "children": [{"type": "minecraft:item", "name": "salmon"}]}]}
                ]}""");

        assertEquals(Map.of(
                "pack:pickle", List.of("minecraft:poisonous_potato"),
                "pack:fish", List.of("minecraft:cod", "minecraft:salmon")), read("pack"));
    }

    @Test
    void tradesAdvancementIconsAndItemPredicatesCount() throws IOException {
        file("pack", "data/pack/villager_trade/coin.json", """
                {"wants": {"id": "minecraft:emerald", "components": {"minecraft:item_model": "pack:coin"}},
                 "gives": {"id": "minecraft:book", "count": 1, "components": {"minecraft:item_model": "pack:tome"}}}""");
        file("pack", "data/pack/advancement/axe.json", """
                {"display": {"icon": {"id": "minecraft:stone_axe", "components": {"minecraft:item_model": "pack:dolabra"}}},
                 "criteria": {"has": {"trigger": "minecraft:inventory_changed", "conditions": {"items": [
                   {"items": ["minecraft:copper_axe", "#minecraft:axes"], "components": {"minecraft:item_model": "pack:dolabra"}}]}}}}""");

        assertEquals(Map.of(
                "pack:coin", List.of("minecraft:emerald"),
                "pack:tome", List.of("minecraft:book"),
                "pack:dolabra", List.of("minecraft:copper_axe", "minecraft:stone_axe")), read("pack"));
    }

    @Test
    void functionsNameItInAnItemArgumentOrAnSnbtCompound() throws IOException {
        file("pack", "data/pack/function/give.mcfunction", """
                # hands the tools out
                give @s minecraft:copper_hoe[custom_data={version:1},minecraft:item_model="pack:mattock"]
                execute as @a[tag=new] run give @s shears[item_model='pack:shears'] 1
                summon item ~ ~ ~ {Item:{id:"minecraft:stick",count:1,components:{"minecraft:item_model":"pack:wand"}}}
                clear @s *[minecraft:item_model="pack:any"]
                execute if entity @s[nbt={SelectedItem:{components:{"minecraft:item_model":"pack:held"}}}] run say hi
                """);

        assertEquals(Map.of(
                "pack:mattock", List.of("minecraft:copper_hoe"),
                "pack:shears", List.of("minecraft:shears"),
                "pack:wand", List.of("minecraft:stick")), read("pack"));
    }

    @Test
    void aZippedDataPackAndAnOverlayFolderAreRead() throws IOException {
        Path zip = directory.resolve("zipped.zip");
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream archive = new ZipOutputStream(out)) {
            archive.putNextEntry(new ZipEntry("pack.mcmeta"));
            archive.write("{}".getBytes(StandardCharsets.UTF_8));
            archive.closeEntry();
            archive.putNextEntry(new ZipEntry("data/pack/recipe/shears.json"));
            archive.write(("{\"result\": {\"id\": \"minecraft:shears\", \"components\": "
                    + "{\"minecraft:item_model\": \"pack:copper_shears\"}}}").getBytes(StandardCharsets.UTF_8));
            archive.closeEntry();
            archive.putNextEntry(new ZipEntry("overlay_26/data/pack/recipe/axe.json"));
            archive.write(("{\"result\": {\"id\": \"minecraft:copper_axe\", \"components\": "
                    + "{\"minecraft:item_model\": \"pack:dolabra\"}}}").getBytes(StandardCharsets.UTF_8));
            archive.closeEntry();
        }

        DatapackItemBases.Result result = DatapackItemBases.read(List.of(zip.toFile()), () -> false);

        assertEquals(Map.of(
                "pack:copper_shears", List.of("minecraft:shears"),
                "pack:dolabra", List.of("minecraft:copper_axe")), result.basesByModel());
        assertEquals(1, result.packsRead());
    }

    @Test
    void filesThatHoldNoItemStacksAndBrokenFilesAreLeftAlone() throws IOException {
        file("pack", "data/pack/worldgen/template_pool/loot.json",
                "{\"id\": \"minecraft:stick\", \"components\": {\"minecraft:item_model\": \"pack:worldgen\"}}");
        file("pack", "data/pack/recipe/broken.json", "{\"result\": {\"id\": \"minecraft:stick\", \"item_model\"");
        file("pack", "data/pack/item_modifier/model_only.json",
                "{\"function\": \"minecraft:set_components\", \"components\": {\"minecraft:item_model\": \"pack:no_base\"}}");
        file("pack", "assets/pack/items/tinder.json",
                "{\"id\": \"minecraft:stick\", \"components\": {\"minecraft:item_model\": \"pack:not_data\"}}");
        file("pack", "data/pack/recipe/modded.json",
                "{\"result\": {\"id\": \"othermod:gear\", \"components\": {\"minecraft:item_model\": \"pack:modded\"}}}");

        DatapackItemBases.Result result = DatapackItemBases.read(
                List.of(directory.resolve("pack").toFile(), directory.resolve("missing").toFile()), () -> false);

        assertEquals(Map.of(), result.basesByModel());
        assertEquals(0, result.packsRead());
    }

    @Test
    void severalDataPacksAddUpAndACancelledReadDeclaresNothing() throws IOException {
        file("first", "data/a/recipe/a.json",
                "{\"result\": {\"id\": \"minecraft:paper\", \"components\": {\"minecraft:item_model\": \"shared:scroll\"}}}");
        file("second", "data/b/recipe/b.json",
                "{\"result\": {\"id\": \"minecraft:book\", \"components\": {\"minecraft:item_model\": \"shared:scroll\"}}}");

        assertEquals(Map.of("shared:scroll", List.of("minecraft:book", "minecraft:paper")), read("first", "second"));
        assertFalse(read("first", "second").isEmpty());
        assertEquals(Map.of(), DatapackItemBases.read(
                List.of(directory.resolve("first").toFile()), () -> true).basesByModel());
    }
}
