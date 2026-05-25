package com.magmaguy.resourcepackmanager.bedrock;

import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.config.BedrockDisplayOffsetsConfig;
import com.magmaguy.resourcepackmanager.config.DefaultConfig;
import com.magmaguy.resourcepackmanager.mixer.engine.MixerLogger;
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
        // Either Geyser-Spigot (local Geyser) or Floodgate (proxy-side Geyser
        // talking back to this backend) signals that a Bedrock client could
        // ever consume the produced pack. Note: in network mode the backend
        // shouldn't actually serve its own Bedrock pack to clients — the proxy
        // does — but we still WANT the conversion to happen on the backend so
        // backend admins can inspect the per-backend Bedrock pack in
        // plugins/ResourcePackManager/output/ for debugging.
        return Bukkit.getPluginManager().getPlugin("Geyser-Spigot") != null
                || Bukkit.getPluginManager().getPlugin("floodgate") != null;
    }

    @Override
    public boolean isBedrockConversionEnabled() {
        return DefaultConfig.isBedrockConversionEnabled();
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
