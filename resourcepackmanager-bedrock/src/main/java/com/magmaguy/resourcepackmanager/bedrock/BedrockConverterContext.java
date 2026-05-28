package com.magmaguy.resourcepackmanager.bedrock;

import com.magmaguy.resourcepackmanager.mixer.engine.MixerLogger;

import java.io.File;

/**
 * Platform-neutral context passed into {@link BedrockConversion}. Implementations
 * carry the platform-specific data (plugin version, target detection, output paths)
 * and effectful platform-specific hooks (mappings deployment).
 *
 * <p>Two intended implementations:
 * <ul>
 *   <li>Bukkit/Spigot backend: reads {@code DefaultConfig}, {@code Bukkit.getPluginManager}
 *       (Geyser-Spigot/Floodgate detection), and copies mappings into the local
 *       {@code plugins/Geyser-*\/custom_mappings/} folder.</li>
 *   <li>Velocity/Bungee proxy: assumes Bedrock is the target (the proxy plugin only
 *       runs in network mode), and copies mappings into the proxy's
 *       {@code plugins/Geyser-Velocity\|Geyser-BungeeCord\|Geyser-*\/custom_mappings/}
 *       folder.</li>
 * </ul></p>
 *
 * <p>Why an interface rather than a record / configuration object: the
 * {@link #deployMappingsIfNeeded(File)} hook is genuinely effectful — it walks a
 * filesystem and decides where to copy a file — and platform-specific path
 * resolution is too gnarly to express as data. The other fields could be a
 * record, but keeping everything together lets each platform have one
 * cohesive context implementation.</p>
 */
public interface BedrockConverterContext {

    /**
     * Logger sink for all conversion-time messages. Reuses the mixer module's
     * {@link MixerLogger} so a platform wrapper can share a single implementation
     * across both the mix and the bedrock-conversion stages.
     */
    MixerLogger logger();

    /**
     * Plugin version string used in the Bedrock manifest's version triplet
     * (e.g. {@code "1.8.0"}). Doesn't strictly need to be a semver — Bedrock
     * cares about the triplet form, not the absolute value.
     */
    String pluginVersion();

    /**
     * True iff a Bedrock client could ever consume this output. Backend impl:
     * Geyser-Spigot OR Floodgate present. Proxy impl: always {@code true} (the
     * proxy plugin only loads when the admin deliberately installed it,
     * implying they have Bedrock players in mind).
     *
     * <p>When this returns {@code false}, {@link BedrockConversion#generate}
     * skips the entire pipeline as pure overhead.</p>
     */
    boolean isBedrockTargetPresent();

    /**
     * True iff Bedrock conversion is enabled in config. Backend reads from
     * {@code DefaultConfig.isBedrockConversionEnabled()}; proxy returns
     * {@code true} unconditionally (no config flag — the whole point of network
     * mode is Bedrock pack delivery).
     */
    boolean isBedrockConversionEnabled();

    /**
     * Deploy the newly-generated mappings JSON to the platform's Geyser
     * {@code custom_mappings/} folder. May be a no-op (e.g. when the platform
     * can't find Geyser, or the admin disabled auto-deploy). Called once per
     * successful conversion.
     */
    void deployMappingsIfNeeded(File mappingsFile);

    /**
     * True iff the operator has opted in to verbose per-item / per-bone progress
     * logging from the Bedrock conversion pipeline. When {@code false} (the
     * default), {@link BedrockLog#debug(String)} calls are no-ops and the
     * console stays quiet except for genuine errors and the per-cycle summary
     * line. Backend impl reads {@code DefaultConfig.isBedrockConverterDebug()};
     * proxy impl can fall through to the {@code false} default unless it adds
     * its own knob.
     */
    default boolean isBedrockConverterDebug() {
        return false;
    }

    /**
     * Snapshot of the 12 user-tunable display-transform offsets passed to
     * {@code FmmAnimationGenerator}. Backend impl reads its YAML-backed config;
     * proxy impl returns {@link BedrockDisplayOffsets.Snapshot#defaults()}
     * (the inherited Rainbow tuning).
     */
    default BedrockDisplayOffsets.Snapshot displayOffsets() {
        return BedrockDisplayOffsets.Snapshot.defaults();
    }

    /**
     * File where the previous run's mappings JSON lives, used for the boot-time
     * pre-deploy step. Returns the file even if it doesn't exist yet — callers
     * check {@code exists()} themselves.
     *
     * <p>Boot-time pre-deploy is needed because Geyser's
     * {@code GeyserDefineCustomItemsEvent} is a once-per-boot lifecycle event,
     * so the custom-item set comes from whatever's in {@code custom_mappings/}
     * at Geyser's startup. RPM's conversion finishes AFTER that point, so the
     * just-generated mappings can only take effect next boot — which we
     * accomplish by writing them to {@code output/} on this boot and copying
     * them to Geyser on the next boot.</p>
     */
    File previousMappingsFile();
}
