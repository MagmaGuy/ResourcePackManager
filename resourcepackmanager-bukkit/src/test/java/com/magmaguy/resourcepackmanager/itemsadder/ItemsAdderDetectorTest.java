package com.magmaguy.resourcepackmanager.itemsadder;

import com.magmaguy.resourcepackmanager.BukkitMockTestSupport;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemsAdderDetectorTest extends BukkitMockTestSupport {

    @Test
    void needsConfigurationWhenItemsAdderIsInstalledButNoHostIsDisabled() throws Exception {
        MockBukkit.createMockPlugin("ItemsAdder", "4.0.0");
        writeItemsAdderConfig(config -> {
            config.set("resource-pack.hosting.self-host.enabled", false);
            config.set("resource-pack.hosting.external-host.url", "http://example.com/resourcepack.zip");
            config.set("resource-pack.hosting.lobfile.enabled", false);
            config.set("resource-pack.hosting.no-host.enabled", false);
            config.set("resource-pack.zip.protect-file-from-unzip.protection_1", false);
            config.set("resource-pack.zip.protect-file-from-unzip.protection_2", false);
            config.set("resource-pack.zip.protect-file-from-unzip.protection_3", false);
            config.set("resource-pack.zip.compress-json-files", false);
        });

        assertTrue(ItemsAdderDetector.isItemsAdderInstalled());
        assertTrue(ItemsAdderDetector.needsConfiguration());
    }

    @Test
    void noHostStillNeedsConfigurationWhenProtectionsOrJsonCompressionAreEnabled() throws Exception {
        MockBukkit.createMockPlugin("ItemsAdder", "4.0.0");
        writeItemsAdderConfig(config -> {
            config.set("resource-pack.hosting.no-host.enabled", true);
            config.set("resource-pack.zip.protect-file-from-unzip.protection_1", true);
            config.set("resource-pack.zip.protect-file-from-unzip.protection_2", false);
            config.set("resource-pack.zip.protect-file-from-unzip.protection_3", false);
            config.set("resource-pack.zip.compress-json-files", true);
        });

        assertTrue(ItemsAdderDetector.hasProtectionEnabled());
        assertTrue(ItemsAdderDetector.hasCompressedJsonEnabled());
        assertTrue(ItemsAdderDetector.needsConfiguration());
    }

    @Test
    void noHostWithReadableJsonAndNoProtectionIsReadyForResourcePackManager() throws Exception {
        MockBukkit.createMockPlugin("ItemsAdder", "4.0.0");
        writeItemsAdderConfig(config -> {
            config.set("resource-pack.hosting.no-host.enabled", true);
            config.set("resource-pack.zip.protect-file-from-unzip.protection_1", false);
            config.set("resource-pack.zip.protect-file-from-unzip.protection_2", false);
            config.set("resource-pack.zip.protect-file-from-unzip.protection_3", false);
            config.set("resource-pack.zip.compress-json-files", false);
        });

        assertFalse(ItemsAdderDetector.isItemsAdderHosting());
        assertFalse(ItemsAdderDetector.hasProtectionEnabled());
        assertFalse(ItemsAdderDetector.hasCompressedJsonEnabled());
        assertFalse(ItemsAdderDetector.needsConfiguration());
    }

    @Test
    void realExternalHostCountsAsItemsAdderHosting() throws Exception {
        MockBukkit.createMockPlugin("ItemsAdder", "4.0.0");
        writeItemsAdderConfig(config -> {
            config.set("resource-pack.hosting.external-host.url", "https://cdn.example.test/pack.zip");
            config.set("resource-pack.hosting.no-host.enabled", false);
        });

        assertTrue(ItemsAdderDetector.isItemsAdderHosting());
        assertFalse(ItemsAdderDetector.needsConfiguration());
    }

    private void writeItemsAdderConfig(ConfigWriter writer) throws Exception {
        File configFile = ItemsAdderDetector.getItemsAdderConfigFile();
        File parent = configFile.getParentFile();
        if (!parent.exists()) {
            assertTrue(parent.mkdirs());
        }
        YamlConfiguration config = new YamlConfiguration();
        writer.write(config);
        config.save(configFile);
    }

    @FunctionalInterface
    private interface ConfigWriter {
        void write(YamlConfiguration config);
    }
}
