package com.magmaguy.resourcepackmanager.thirdparty;

import com.magmaguy.resourcepackmanager.BukkitMockTestSupport;
import com.magmaguy.resourcepackmanager.config.DefaultConfig;
import com.magmaguy.resourcepackmanager.config.compatibleplugins.CompatiblePluginConfigFields;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThirdPartyResourcePackTest extends BukkitMockTestSupport {

    @BeforeEach
    void resetPackRegistry() throws Exception {
        ThirdPartyResourcePack.shutdown();
        setStaticField(DefaultConfig.class, "priorityOrder", List.of());
    }

    @AfterEach
    void shutdownRegistry() {
        ThirdPartyResourcePack.shutdown();
    }

    @Test
    void localAndAdditionalLocalSourcesRegisterSeparateMixerInputs() throws Exception {
        MockBukkit.createMockPlugin("ExamplePack", "1.0");
        Path plugins = plugin.getDataFolder().getParentFile().toPath();
        Files.createDirectories(plugins.resolve("ExamplePack/shared"));
        Files.writeString(plugins.resolve("ExamplePack/resourcepack.zip"), "zip bytes");
        Files.writeString(plugins.resolve("ExamplePack/shared/pack.mcmeta"), "{}");

        CompatiblePluginConfigFields fields = new CompatiblePluginConfigFields("example.yml", true);
        fields.setPluginName("ExamplePack");
        fields.setLocalPath("ExamplePack/resourcepack.zip");
        fields.setAdditionalLocalPath("ExamplePack/shared");
        fields.setUrl("https://cdn.example.test/fallback.zip");
        fields.setReloadCommand("example reload");

        ThirdPartyResourcePack.initializeThirdPartyResourcePack(fields);

        Set<String> mixerFilenames = ThirdPartyResourcePack.thirdPartyResourcePacks.stream()
                .map(ThirdPartyResourcePack::getMixerFilename)
                .collect(Collectors.toSet());
        assertEquals(Set.of(
                "ExamplePack_resource_pack.zip",
                "ExamplePack_shared_resource_pack.zip"), mixerFilenames);
        assertTrue(ThirdPartyResourcePack.thirdPartyResourcePacks.stream()
                .allMatch(pack -> pack.isEnabled() && pack.isDone()));
    }

    @Test
    void urlRegistersWhenConfiguredLocalSourceIsMissing() throws Exception {
        MockBukkit.createMockPlugin("RemoteOnlyPack", "1.0");
        CompatiblePluginConfigFields fields = new CompatiblePluginConfigFields("remote.yml", true);
        fields.setPluginName("RemoteOnlyPack");
        fields.setLocalPath("RemoteOnlyPack/missing.zip");
        fields.setUrl("https://cdn.example.test/pack.zip");
        fields.setReloadCommand("remote reload");

        ThirdPartyResourcePack.initializeThirdPartyResourcePack(fields);

        assertEquals(1, ThirdPartyResourcePack.thirdPartyResourcePacks.size());
        ThirdPartyResourcePack pack = ThirdPartyResourcePack.thirdPartyResourcePacks.iterator().next();
        assertEquals("RemoteOnlyPack_resource_pack.zip", pack.getMixerFilename());
        assertTrue(pack.isEnabled());
        assertTrue(pack.isDone());
        assertNull(pack.getFile());
    }
}
