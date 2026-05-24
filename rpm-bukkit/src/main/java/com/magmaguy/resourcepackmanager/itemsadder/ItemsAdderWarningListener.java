package com.magmaguy.resourcepackmanager.itemsadder;

import com.magmaguy.magmacore.util.ChatColorConverter;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Listens for OP player joins and warns them if ItemsAdder needs configuration.
 */
public class ItemsAdderWarningListener implements Listener {

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Only warn OP players
        if (!player.isOp()) return;

        // Check if this player has dismissed the warning
        if (ItemsAdderDismissedConfig.hasDismissed(player.getUniqueId())) return;

        // Check if ItemsAdder needs configuration
        if (!ItemsAdderDetector.needsConfiguration()) return;

        // Delay the warning slightly to let the player fully join
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) return;

                sendWarning(player);
            }
        }.runTaskLater(ResourcePackManager.plugin, 60L); // 3 seconds after join
    }

    /**
     * Send the warning title and chat message to the player.
     */
    private void sendWarning(Player player) {
        // Send title/subtitle
        player.sendTitle(
                ChatColorConverter.convert("&c&lItemsAdder Detected"),
                ChatColorConverter.convert("&eResource pack not configured - check chat!"),
                10, 70, 20
        );

        // Send chat message with explanation
        player.sendMessage("");
        player.sendMessage(ChatColorConverter.convert("&8&m----------------------------------------"));
        player.sendMessage(ChatColorConverter.convert("&c&lItemsAdder Configuration Warning"));
        player.sendMessage(ChatColorConverter.convert("&8&m----------------------------------------"));
        player.sendMessage("");
        player.sendMessage(ChatColorConverter.convert("&eItemsAdder has been detected but is not currently"));
        player.sendMessage(ChatColorConverter.convert("&econfigured to let ResourcePackManager host the resource pack."));
        player.sendMessage("");
        player.sendMessage(ChatColorConverter.convert("&7For ResourcePackManager to merge and host the resource pack,"));
        player.sendMessage(ChatColorConverter.convert("&7ItemsAdder needs to have hosting disabled and protections off."));
        player.sendMessage("");

        // Create clickable buttons
        // Option 1: Configure automatically
        TextComponent configureButton = new TextComponent(ChatColorConverter.convert("&a&l[Configure Automatically]"));
        configureButton.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/rspm itemsadder configure"));
        configureButton.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new Text(ChatColorConverter.convert("&aClick to automatically configure ItemsAdder\n&7This will:\n&7- Enable no-host mode\n&7- Disable file protections\n&7- Reload both plugins"))));

        // Option 2: Dismiss permanently
        TextComponent dismissButton = new TextComponent(ChatColorConverter.convert("&c&l[Dismiss Permanently]"));
        dismissButton.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/rspm itemsadder dismiss"));
        dismissButton.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new Text(ChatColorConverter.convert("&cClick to dismiss this warning permanently\n&7You won't see this warning again"))));

        // Send the buttons
        TextComponent space = new TextComponent("  ");
        player.spigot().sendMessage(configureButton, space, dismissButton);

        player.sendMessage("");
        player.sendMessage(ChatColorConverter.convert("&8&m----------------------------------------"));
        player.sendMessage("");
    }
}
