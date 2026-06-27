package com.magmaguy.resourcepackmanager.utils;

import org.bukkit.Bukkit;

public class ServerVersionHelper {
    private static final int majorVersion;
    private static final int minorVersion;
    private static final boolean supportsMultipleResourcePacks;

    static {
        String version = Bukkit.getBukkitVersion(); // e.g., "1.20.4-R0.1-SNAPSHOT" or "26.1-R0.1-SNAPSHOT"
        String[] parts = version.split("-")[0].split("\\.");

        if (parts[0].equals("1")) {
            // Legacy format: 1.MAJOR.MINOR
            majorVersion = Integer.parseInt(parts[1]);
            minorVersion = parts.length >= 3 ? Integer.parseInt(parts[2]) : 0;
        } else {
            // New year.drop format: MAJOR.MINOR (e.g. 26.1)
            majorVersion = Integer.parseInt(parts[0]);
            minorVersion = parts.length >= 2 ? Integer.parseInt(parts[1]) : 0;
        }

        // addResourcePack was added in 1.20.3 (majorVersion=20, minorVersion>=3)
        // All versions >= 26 support it
        supportsMultipleResourcePacks = majorVersion > 20 || (majorVersion == 20 && minorVersion >= 3);
    }

    public static boolean supportsMultipleResourcePacks() {
        return supportsMultipleResourcePacks;
    }

    public static int getMajorVersion() {
        return majorVersion;
    }

    public static int getMinorVersion() {
        return minorVersion;
    }
}
