package com.magmaguy.resourcepackmanager.utils;

import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class VersionChecker {
    private static boolean pluginIsUpToDate = true;

    private VersionChecker() {
    }

    public static void checkPluginVersion() {
        new BukkitRunnable() {
            @Override
            public void run() {
                String currentVersion = ResourcePackManager.plugin.getDescription().getVersion();
                boolean snapshot = false;
                if (currentVersion.contains("SNAPSHOT")) {
                    snapshot = true;
                    currentVersion = currentVersion.split("-")[0];
                }
                String publicVersion = "";

                try {
                    Logger.info("Latest public release is " + VersionChecker.readStringFromURL("https://api.spigotmc.org/legacy/update.php?resource=118574"));
                    Logger.info("Your version is " + ResourcePackManager.plugin.getDescription().getVersion());
                    publicVersion = VersionChecker.readStringFromURL("https://api.spigotmc.org/legacy/update.php?resource=118574");
                } catch (IOException e) {
                    Logger.warn("Couldn't check latest version");
                    return;
                }

                if (Double.parseDouble(currentVersion.split("\\.")[0]) < Double.parseDouble(publicVersion.split("\\.")[0])) {
                    outOfDateHandler();
                    return;
                }

                if (Double.parseDouble(currentVersion.split("\\.")[0]) == Double.parseDouble(publicVersion.split("\\.")[0])) {

                    if (Double.parseDouble(currentVersion.split("\\.")[1]) < Double.parseDouble(publicVersion.split("\\.")[1])) {
                        outOfDateHandler();
                        return;
                    }

                    if (Double.parseDouble(currentVersion.split("\\.")[1]) == Double.parseDouble(publicVersion.split("\\.")[1])) {
                        if (Double.parseDouble(currentVersion.split("\\.")[2]) < Double.parseDouble(publicVersion.split("\\.")[2])) {
                            outOfDateHandler();
                            return;
                        }
                    }
                }

                if (!snapshot)
                    Logger.info("You are running the latest version!");
                else
                    Logger.info("You are running a snapshot version! You can check for updates in the #releases channel on the Nightbreak Discord!");

                pluginIsUpToDate = true;
            }
        }.runTaskAsynchronously(ResourcePackManager.plugin);
    }

    private static String readStringFromURL(String url) throws IOException {

        try (Scanner scanner = new Scanner(new URL(url).openStream(),
                StandardCharsets.UTF_8)) {
            scanner.useDelimiter("\\A");
            return scanner.hasNext() ? scanner.next() : "";
        }

    }

    private static void outOfDateHandler() {
        Logger.warn("A newer version of this plugin is available for download!");
        pluginIsUpToDate = false;
    }

    public static class VersionCheckerEvents implements Listener {
        @EventHandler
        public void onPlayerLogin(PlayerJoinEvent event) {

            if (!event.getPlayer().hasPermission("elitemobs.versionnotification")) return;

            new BukkitRunnable() {
                @Override
                public void run() {
                    if (!event.getPlayer().isOnline()) return;
                    if (!pluginIsUpToDate)
                        event.getPlayer().sendMessage(com.magmaguy.magmacore.util.ChatColorConverter.convert("&cYour version of ResourcePackManager is outdated." +
                                " &aYou can download the latest version from &3&n&ohttps://www.spigotmc.org/resources/resource-pack-manager.118574/"));
                }
            }.runTaskLater(ResourcePackManager.plugin, 20L * 3);

        }
    }

}
