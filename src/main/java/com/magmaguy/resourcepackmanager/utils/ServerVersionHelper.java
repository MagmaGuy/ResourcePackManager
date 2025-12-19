package com.magmaguy.resourcepackmanager.utils;

import org.bukkit.Bukkit;

public class ServerVersionHelper {
    private static final int majorVersion;
    private static final int minorVersion;
    private static final boolean supportsMultipleResourcePacks;

    static {
        String version = Bukkit.getBukkitVersion(); // e.g., "1.20.4-R0.1-SNAPSHOT"
        String[] parts = version.split("-")[0].split("\\.");
        majorVersion = Integer.parseInt(parts[0]);
        minorVersion = Integer.parseInt(parts[1]);

        // addResourcePack was added in 1.20.3
        supportsMultipleResourcePacks = majorVersion > 1 || (majorVersion == 1 && minorVersion >= 20 && getRevision(version) >= 3);
    }

    private static int getRevision(String version) {
        String[] parts = version.split("-")[0].split("\\.");
        if (parts.length >= 3) {
            try {
                return Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
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
