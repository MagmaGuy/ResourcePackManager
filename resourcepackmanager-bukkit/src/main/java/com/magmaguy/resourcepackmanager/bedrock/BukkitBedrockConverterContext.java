package com.magmaguy.resourcepackmanager.bedrock;

import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.config.BedrockDisplayOffsetsConfig;
import com.magmaguy.resourcepackmanager.config.DefaultConfig;
import com.magmaguy.resourcepackmanager.mixer.engine.MixerLogger;
import com.magmaguy.resourcepackmanager.network.NetworkMode;
import org.bukkit.Bukkit;

import java.io.File;

/**
 * Backend (Bukkit) implementation of {@link BedrockConverterContext}. Wires the
 * platform-neutral converter up to the Spigot plugin's {@code DefaultConfig},
 * {@code Bukkit.getPluginManager} (for Geyser/Floodgate detection), and
 * {@link GeyserDeployer} (for path resolution into
 * {@code plugins/Geyser-*\/custom_mappings/}).
 *
 * <p>Singleton — there's only one RPM plugin instance per JVM. Held by
 * {@code ResourcePackManager} and reused by both {@link BedrockConversion#generate}
 * and the boot-time {@link BedrockConversion#deployPreviousMappingsIfNeeded} call.</p>
 */
public final class BukkitBedrockConverterContext implements BedrockConverterContext {

    /**
     * Adapter exposing MagmaCore's static {@code Logger} as the converter
     * module's {@code MixerLogger} interface. Lets every conversion message
     * land in the Bukkit console via the existing RPM logger formatting.
     */
    private static final MixerLogger LOGGER = new MixerLogger() {
        @Override public void info(String m) { Logger.info(m); }
        @Override public void warn(String m) { Logger.warn(m); }
        @Override public void collision(String m) { /* not used by the bedrock pipeline */ }
    };

    public BukkitBedrockConverterContext() {
        // No state — the impl reads from static config classes and Bukkit.getPluginManager
        // at call time, which is fine because both update on /reload and we don't need
        // to cache anything.
    }

    @Override
    public MixerLogger logger() {
        return LOGGER;
    }

    @Override
    public String pluginVersion() {
        return ResourcePackManager.plugin.getDescription().getVersion();
    }

    @Override
    public boolean isBedrockTargetPresent() {
        // Three independent reasons a Bedrock pack might be consumed downstream:
        //
        // 1. Local Geyser-Spigot — Bedrock players hit this backend directly
        //    through a Geyser instance on the same JVM. Pack served locally.
        // 2. Local Floodgate (without proxy) — covers standalone setups where
        //    Floodgate runs on the backend and pairs with some other Geyser.
        // 3. Network mode — proxy has Geyser+Floodgate, this backend has
        //    neither but its converted Bedrock pack is fetched over HTTP by
        //    the proxy plugin and merged into the network-wide pack. This is
        //    THE common production case (proxy-fronted multi-backend network),
        //    and missing it here caused the backend to silently skip Bedrock
        //    conversion and serve a 404 on /bedrock.zip to the proxy — see the
        //    Dec 2026 user report where a Velocity-forwarded Paper backend had
        //    no Floodgate/Geyser locally and produced no Bedrock pack despite
        //    NetworkMode reporting ACTIVE via the paper-global.yml signal.
        return Bukkit.getPluginManager().getPlugin("Geyser-Spigot") != null
                || Bukkit.getPluginManager().getPlugin("floodgate") != null
                || NetworkMode.isActive();
    }

    @Override
    public boolean isBedrockConversionEnabled() {
        return DefaultConfig.isBedrockConversionEnabled();
    }

    @Override
    public boolean isBedrockConverterDebug() {
        return DefaultConfig.isBedrockConverterDebug();
    }

    @Override
    public void deployMappingsIfNeeded(File mappingsFile) {
        if (!DefaultConfig.isBedrockAutoDeployToGeyser()) return;
        GeyserDeployer.deployMappings(mappingsFile);
    }

    @Override
    public File previousMappingsFile() {
        File outputDir = new File(ResourcePackManager.plugin.getDataFolder(), "output");
        return new File(outputDir, BedrockConversion.GEYSER_MAPPINGS_NAME);
    }

    @Override
    public BedrockDisplayOffsets.Snapshot displayOffsets() {
        // Pulls the live YAML-backed values from BedrockDisplayOffsetsConfig.
        // Snapshot is captured once per generate() call inside BedrockConversion,
        // so a /reload between calls cleanly picks up new values on the next mix.
        return new BedrockDisplayOffsets.Snapshot(
                BedrockDisplayOffsetsConfig.getFirstPersonBaseRotationX(),
                BedrockDisplayOffsetsConfig.getFirstPersonBaseRotationY(),
                BedrockDisplayOffsetsConfig.getFirstPersonBaseRotationZ(),
                BedrockDisplayOffsetsConfig.getFirstPersonBasePositionX(),
                BedrockDisplayOffsetsConfig.getFirstPersonBasePositionY(),
                BedrockDisplayOffsetsConfig.getFirstPersonBasePositionZ(),
                BedrockDisplayOffsetsConfig.getThirdPersonBaseRotationX(),
                BedrockDisplayOffsetsConfig.getThirdPersonBaseRotationY(),
                BedrockDisplayOffsetsConfig.getThirdPersonBaseRotationZ(),
                BedrockDisplayOffsetsConfig.getThirdPersonBasePositionX(),
                BedrockDisplayOffsetsConfig.getThirdPersonBasePositionY(),
                BedrockDisplayOffsetsConfig.getThirdPersonBasePositionZ());
    }
}
