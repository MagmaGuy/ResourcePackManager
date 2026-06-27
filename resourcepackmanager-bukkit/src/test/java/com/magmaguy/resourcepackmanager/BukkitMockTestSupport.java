package com.magmaguy.resourcepackmanager;

import com.magmaguy.magmacore.MagmaCore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.lang.reflect.Field;

public abstract class BukkitMockTestSupport {

    protected ServerMock server;
    protected PluginMock plugin;

    @BeforeEach
    void setUpMockBukkit() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("ResourcePackManager", "2.2.1");
        ResourcePackManager.plugin = plugin;
        MagmaCore.createInstance(plugin);
        resetNetworkModeCache();
    }

    @AfterEach
    void tearDownMockBukkit() throws Exception {
        try {
            if (plugin != null) {
                MagmaCore.shutdown(plugin);
            }
            ResourcePackManager.plugin = null;
            resetNetworkModeCache();
        } finally {
            MockBukkit.unmock();
        }
    }

    protected static void setStaticField(Class<?> type, String fieldName, Object value) throws Exception {
        Field field = type.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }

    protected static Object getStaticField(Class<?> type, String fieldName) throws Exception {
        Field field = type.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(null);
    }

    protected static void resetNetworkModeCache() throws Exception {
        setStaticField(com.magmaguy.resourcepackmanager.network.NetworkMode.class, "cached", null);
    }
}
