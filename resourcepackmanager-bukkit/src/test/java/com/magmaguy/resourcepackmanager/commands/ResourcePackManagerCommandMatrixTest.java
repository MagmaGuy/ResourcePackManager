package com.magmaguy.resourcepackmanager.commands;

import com.magmaguy.magmacore.MagmaCore;
import com.magmaguy.magmacore.command.CommandManager;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.autohost.AutoHost;
import com.magmaguy.resourcepackmanager.itemsadder.ItemsAdderCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcePackManagerCommandMatrixTest {
    private ServerMock server;
    private PluginMock plugin;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.loadWith(PluginMock.class, new ByteArrayInputStream("""
                name: ResourcePackManager
                version: 2.2.1-test
                main: org.mockbukkit.mockbukkit.plugin.PluginMock
                api-version: '1.21'
                commands:
                  resourcepackmanager:
                    aliases:
                      - rspm
                permissions:
                  resourcepackmanager.*:
                    default: op
                """.getBytes(StandardCharsets.UTF_8)));
        ResourcePackManager.plugin = plugin;
        resetMagmaCore();
        MagmaCore.createInstance(plugin);

        CommandManager commandManager = new CommandManager(plugin, "resourcepackmanager");
        commandManager.registerCommand(new ReloadCommand());
        commandManager.registerCommand(new DataComplianceRequestCommand());
        commandManager.registerCommand(new ItemsAdderCommand());
        commandManager.registerCommand(new StatusCommand());
    }

    @AfterEach
    void tearDown() throws Exception {
        AutoHost.shutdown();
        CommandManager.shutdown();
        MagmaCore.shutdown(plugin);
        ResourcePackManager.plugin = null;
        resetMagmaCore();
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    @Test
    void pluginYmlDeclaresUniversalCommandAndAlias() {
        YamlConfiguration pluginYml = loadRealPluginYml();

        assertTrue(pluginYml.isConfigurationSection("commands.resourcepackmanager"));
        assertEquals(List.of("rspm"), pluginYml.getStringList("commands.resourcepackmanager.aliases"));
    }

    @Test
    void commandMapDispatchesDiagnosticsProviderGuardsAndReloadBody() {
        assertTrue(server.dispatchCommand(server.getConsoleSender(), "rspm status"));
        assertConsoleMessageContains("RSPM Status");

        assertTrue(server.dispatchCommand(server.getConsoleSender(), "resourcepackmanager data_compliance_request"));
        assertConsoleMessageContains("auto-hoster is either disabled or not working");

        assertTrue(server.dispatchCommand(server.getConsoleSender(), "rspm itemsadder configure"));
        assertConsoleMessageContains("ItemsAdder is not installed");

        assertTrue(server.dispatchCommand(server.getConsoleSender(), "rspm itemsadder dismiss"));
        assertConsoleMessageContains("only be used by players");

        assertTrue(server.dispatchCommand(server.getConsoleSender(), "rspm reload"));
        assertConsoleMessageContains("Reloaded the plugin");
    }

    private void assertConsoleMessageContains(String expected) {
        for (int i = 0; i < 80; i++) {
            String message = server.getConsoleSender().nextMessage();
            if (message != null && message.contains(expected)) {
                return;
            }
        }
        fail("Expected console output to contain: " + expected);
    }

    private static YamlConfiguration loadRealPluginYml() {
        YamlConfiguration configuration = new YamlConfiguration();
        try (InputStreamReader reader = new InputStreamReader(
                ResourcePackManagerCommandMatrixTest.class.getResourceAsStream("/plugin.yml"),
                StandardCharsets.UTF_8)) {
            configuration.load(reader);
        } catch (Exception exception) {
            throw new AssertionError("Failed to load ResourcePackManager plugin.yml", exception);
        }
        return configuration;
    }

    @SuppressWarnings("unchecked")
    private static void resetMagmaCore() throws Exception {
        Field instanceField = MagmaCore.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, null);

        Field registeredPluginsField = MagmaCore.class.getDeclaredField("registeredPlugins");
        registeredPluginsField.setAccessible(true);
        ((Map<String, ?>) registeredPluginsField.get(null)).clear();

        Field listenerRegistrationsField = MagmaCore.class.getDeclaredField("listenerRegistrations");
        listenerRegistrationsField.setAccessible(true);
        ((java.util.Set<String>) listenerRegistrationsField.get(null)).clear();
    }
}
