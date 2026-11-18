package com.magmaguy.resourcepackmanager.bedrock;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.packs.DataPack;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * The data packs in the server's {@code datapacks} folder that are not switched off, for
 * {@link com.magmaguy.resourcepackmanager.bedrock.generic.DatapackItemBases}. The server loads
 * data packs from its main world only.
 */
final class ServerDatapacks {

    private ServerDatapacks() {
    }

    static List<File> enabled() {
        List<World> worlds = Bukkit.getWorlds();
        World mainWorld = worlds.isEmpty() ? null : worlds.get(0);
        File folder = mainWorld != null
                ? datapacksFolder(mainWorld.getWorldFolder(), Bukkit.getWorldContainer())
                : datapacksFolderBeforeWorldsLoad(new File("server.properties"), Bukkit.getWorldContainer());
        return packsIn(folder, mainWorld == null ? Set.of() : disabledTitles(mainWorld));
    }

    /**
     * The {@code datapacks} folder of the world that {@code worldFolder} belongs to. Since 26.1
     * a dimension sits in {@code <world>/dimensions/<namespace>/<path>}, so the folder can be a
     * few levels above the one Bukkit reports.
     */
    static File datapacksFolder(File worldFolder, File worldContainer) {
        File stop = worldContainer == null ? null : worldContainer.getAbsoluteFile();
        File folder = worldFolder == null ? null : worldFolder.getAbsoluteFile();
        for (int level = 0; level < 5 && folder != null; level++) {
            File datapacks = new File(folder, "datapacks");
            if (datapacks.isDirectory()) return datapacks;
            if (folder.equals(stop)) return null;
            folder = folder.getParentFile();
        }
        return null;
    }

    /** Before any world exists, {@code level-name} in server.properties names the main world. */
    static File datapacksFolderBeforeWorldsLoad(File serverProperties, File worldContainer) {
        if (!serverProperties.isFile()) return null;
        Properties properties = new Properties();
        try (InputStream in = new FileInputStream(serverProperties)) {
            properties.load(in);
        } catch (IOException | IllegalArgumentException unreadable) {
            return null;
        }
        String levelName = properties.getProperty("level-name", "world").trim();
        if (levelName.isEmpty()) return null;
        File datapacks = new File(new File(worldContainer, levelName), "datapacks");
        return datapacks.isDirectory() ? datapacks : null;
    }

    /** Data pack folders and zips in {@code folder}, without those the server has switched off. */
    static List<File> packsIn(File folder, Set<String> disabledTitles) {
        File[] children = folder == null ? null : folder.listFiles();
        if (children == null) return List.of();
        Arrays.sort(children, Comparator.comparing(File::getName));
        List<File> packs = new ArrayList<>();
        for (File child : children) {
            boolean zip = child.isFile() && child.getName().toLowerCase(Locale.ROOT).endsWith(".zip");
            boolean pack = zip || (child.isDirectory() && new File(child, "pack.mcmeta").isFile());
            if (pack && !disabledTitles.contains(child.getName())) packs.add(child);
        }
        return packs;
    }

    /**
     * The server titles a world data pack by its file name. Only a pack the server reports as
     * switched off is left out: when the answer is unavailable, every pack in the folder counts.
     */
    private static Set<String> disabledTitles(World world) {
        Set<String> titles = new HashSet<>();
        try {
            for (DataPack pack : Bukkit.getDataPackManager().getDisabledDataPacks(world))
                if (pack.getSource() == DataPack.Source.WORLD) titles.add(pack.getTitle());
        } catch (RuntimeException | LinkageError unavailable) {
            return Set.of();
        }
        return titles;
    }
}
