package com.magmaguy.resourcepackmanager.bedrock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class ServerDatapacksTest {
    @TempDir
    Path server;

    private File datapacks() throws IOException {
        Path datapacks = server.resolve("world/datapacks");
        BedrockMappingFixture.write(datapacks.resolve("folder_pack/pack.mcmeta"), "{}");
        BedrockMappingFixture.write(datapacks.resolve("Zipped_Pack.zip"), "zip");
        BedrockMappingFixture.write(datapacks.resolve("Switched_Off.zip"), "zip");
        BedrockMappingFixture.write(datapacks.resolve("notes.txt"), "not a pack");
        Files.createDirectories(datapacks.resolve("not_a_pack"));
        return datapacks.toFile();
    }

    @Test
    void theFolderIsFoundFromTheWorldFolderOrADimensionFolderInsideIt() throws IOException {
        File datapacks = datapacks();
        Path dimension = Files.createDirectories(server.resolve("world/dimensions/minecraft/overworld"));

        assertEquals(datapacks, ServerDatapacks.datapacksFolder(server.resolve("world").toFile(), server.toFile()));
        assertEquals(datapacks, ServerDatapacks.datapacksFolder(dimension.toFile(), server.toFile()));
        assertNull(ServerDatapacks.datapacksFolder(
                Files.createDirectories(server.resolve("other_world")).toFile(), server.toFile()),
                "the search stops at the world container");
    }

    @Test
    void beforeWorldsLoadTheLevelNameFindsIt() throws IOException {
        File datapacks = datapacks();
        BedrockMappingFixture.write(server.resolve("server.properties"), "motd=hi\nlevel-name=world\n");
        BedrockMappingFixture.write(server.resolve("other.properties"), "level-name=missing\n");

        assertEquals(datapacks, ServerDatapacks.datapacksFolderBeforeWorldsLoad(
                server.resolve("server.properties").toFile(), server.toFile()));
        assertNull(ServerDatapacks.datapacksFolderBeforeWorldsLoad(
                server.resolve("other.properties").toFile(), server.toFile()));
        assertNull(ServerDatapacks.datapacksFolderBeforeWorldsLoad(
                server.resolve("absent.properties").toFile(), server.toFile()));
    }

    @Test
    void foldersWithPackMetaAndZipsCountUnlessTheServerSwitchedThemOff() throws IOException {
        File datapacks = datapacks();

        assertEquals(List.of("Switched_Off.zip", "Zipped_Pack.zip", "folder_pack"),
                ServerDatapacks.packsIn(datapacks, Set.of()).stream().map(File::getName).toList());
        assertEquals(List.of("Zipped_Pack.zip", "folder_pack"),
                ServerDatapacks.packsIn(datapacks, Set.of("Switched_Off.zip")).stream().map(File::getName).toList());
        assertEquals(List.of(), ServerDatapacks.packsIn(null, Set.of()));
    }
}
