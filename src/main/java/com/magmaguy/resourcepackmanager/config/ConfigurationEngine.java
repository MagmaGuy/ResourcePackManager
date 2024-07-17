package com.magmaguy.resourcepackmanager.config;

import com.magmaguy.resourcepackmanager.Logger;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.utils.ChatColorConverter;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class ConfigurationEngine {

    public static File fileCreator(String path, String fileName) {
        File file = new File(ResourcePackManager.plugin.getDataFolder().getPath() + "/" + path + "/", fileName);
        return fileCreator(file);
    }

    public static File fileCreator(String fileName) {
        File file = new File(ResourcePackManager.plugin.getDataFolder().getPath(), fileName);
        return fileCreator(file);
    }

    public static File fileCreator(File file) {

        if (!file.exists())
            try {
                file.getParentFile().mkdirs();
                file.createNewFile();
            } catch (IOException ex) {
                Bukkit.getLogger().warning("[EliteMobs] Error generating the plugin file: " + file.getName());
            }

        return file;

    }

    public static FileConfiguration fileConfigurationCreator(File file) {
        try {
            return YamlConfiguration.loadConfiguration(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
        } catch (Exception exception) {
            Logger.warn("Failed to read configuration from file " + file.getName());
            return null;
        }
    }

    public static void fileSaverCustomValues(FileConfiguration fileConfiguration, File file) {
        fileConfiguration.options().copyDefaults(true);

        try {
            fileConfiguration.save(file);
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    public static void fileSaverOnlyDefaults(FileConfiguration fileConfiguration, File file) {
        fileConfiguration.options().copyDefaults(true);
        UnusedNodeHandler.clearNodes(fileConfiguration);

        try {
            fileConfiguration.save(file);
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private static void setComments(FileConfiguration fileConfiguration, String key, List<String> comments) {
        fileConfiguration.setComments(key, comments);
    }

    public static Boolean setBoolean(FileConfiguration fileConfiguration, String key, boolean defaultValue) {
        fileConfiguration.addDefault(key, defaultValue);
        return fileConfiguration.getBoolean(key);
    }

    public static Boolean setBoolean(List<String> comments, FileConfiguration fileConfiguration, String key, boolean defaultValue) {
        boolean value = setBoolean(fileConfiguration, key, defaultValue);
        setComments(fileConfiguration, key, comments);
        return value;
    }


    public static int setInt(FileConfiguration fileConfiguration, String key, int defaultValue) {
        fileConfiguration.addDefault(key, defaultValue);
        return fileConfiguration.getInt(key);
    }

    public static int setInt(List<String> comments, FileConfiguration fileConfiguration, String key, int defaultValue) {
        int value = setInt(fileConfiguration, key, defaultValue);
        setComments(fileConfiguration, key, comments);
        return value;
    }

    public static double setDouble(FileConfiguration fileConfiguration, String key, double defaultValue) {
        fileConfiguration.addDefault(key, defaultValue);
        return fileConfiguration.getDouble(key);
    }

    public static double setDouble(List<String> comments, FileConfiguration fileConfiguration, String key, double defaultValue) {
        double value = setDouble(fileConfiguration, key, defaultValue);
        setComments(fileConfiguration, key, comments);
        return value;
    }

    public static boolean writeValue(Object value, File file, FileConfiguration fileConfiguration, String path) {
        fileConfiguration.set(path, value);
        try {
            fileSaverCustomValues(fileConfiguration, file);
        } catch (Exception exception) {
            Logger.warn("Failed to write value for " + path + " in file " + file.getName());
            return false;
        }
        return true;
    }

    public static void removeValue(File file, FileConfiguration fileConfiguration, String path) {
        writeValue(null, file, fileConfiguration, path);
    }

    public static List setList(File file, FileConfiguration fileConfiguration, String key, List defaultValue) {
        fileConfiguration.addDefault(key, defaultValue);
        return fileConfiguration.getList(key);
    }

    public static List setList(List<String> comment, File file, FileConfiguration fileConfiguration, String key, List defaultValue) {
        List value = setList(file, fileConfiguration, key, defaultValue);
        setComments(fileConfiguration, key, comment);
        return value;
    }
    public static String setString(File file, FileConfiguration fileConfiguration, String key, String defaultValue) {
        return ChatColorConverter.convert(fileConfiguration.getString(key));
    }

    public static String setString(List<String> comments, File file, FileConfiguration fileConfiguration, String key, String defaultValue) {
        String value = setString(file, fileConfiguration, key, defaultValue);
        setComments(fileConfiguration, key, comments);
        return value;
    }

}
