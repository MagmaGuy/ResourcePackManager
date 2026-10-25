package com.magmaguy.resourcepackmanager.bedrock;

import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.ResourcePackManager;
import com.magmaguy.resourcepackmanager.config.DefaultConfig;
import com.magmaguy.resourcepackmanager.mixer.engine.MixerLogger;
import com.magmaguy.resourcepackmanager.network.NetworkMode;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;

/**
 * Backend (Bukkit) implementation of {@link BedrockConverterContext}. Wires the
 * platform-neutral converter up to the Spigot plugin's {@code DefaultConfig},
 * {@code Bukkit.getPluginManager} (for Geyser/Floodgate detection), and
 * {@link GeyserDeployer} (for path resolution into
 * {@code plugins/Geyser-*\/custom_mappings/}).
 *
 * <p>A context is cheap and created for each conversion so it can carry that
 * run's cancellation token. Configuration and platform state are still read
 * live rather than cached.</p>
 */
public final class BukkitBedrockConverterContext implements BedrockConverterContext {
    private final BooleanSupplier runCancellation;
    /** Read once per context: the mix identity and the conversion must see the same set. */
    private Map<String, List<String>> declaredBaseItems;

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
        this(() -> false);
    }

    public BukkitBedrockConverterContext(BooleanSupplier runCancellation) {
        this.runCancellation = runCancellation == null ? () -> false : runCancellation;
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
    public synchronized Map<String, List<String>> declaredBaseItems() {
        if (declaredBaseItems == null) {
            Map<String, List<String>> read = Map.of();
            try {
                Plugin oraxen = Bukkit.getPluginManager().getPlugin("Oraxen");
                if (oraxen != null) read = OraxenItemBases.read(oraxen.getDataFolder());
            } catch (RuntimeException unreadable) {
                // A config RSPM cannot read only costs these items their declared base;
                // the filename heuristic still maps them as before.
                Logger.warn("Could not read Oraxen item materials for Bedrock conversion: " + unreadable.getMessage());
            }
            declaredBaseItems = Map.copyOf(read);
        }
        return declaredBaseItems;
    }

    /**
     * Short digest of {@link #declaredBaseItems()} for the mix identity, so changing an
     * Oraxen item's material reconverts even when the pack bytes stay the same. Empty
     * when nothing is declared, which keeps the identity of servers without Oraxen.
     */
    public String declaredBaseItemsDigest() {
        Map<String, List<String>> declared = declaredBaseItems();
        if (declared.isEmpty()) return "";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest(new TreeMap<>(declared).toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException unavailable) {
            return Integer.toHexString(new TreeMap<>(declared).toString().hashCode());
        }
    }

    @Override
    public boolean isCancellationRequested() {
        return Thread.currentThread().isInterrupted()
                || runCancellation.getAsBoolean()
                || com.magmaguy.magmacore.MagmaCore.isShutdownRequested(ResourcePackManager.plugin);
    }

    @Override
    public void deployMappingsIfNeeded(File mappingsFile) {
        GeyserDeployer.reconcileMappings(
                mappingsFile,
                DefaultConfig.isBedrockAutoDeployToGeyser());
    }

    @Override
    public File deployedMappingsFile() {
        if (!DefaultConfig.isBedrockAutoDeployToGeyser()) return null;
        return GeyserDeployer.mappingsTarget();
    }

    @Override
    public File previousDeployedMappingsFile() {
        return GeyserDeployer.ownedMappingsTarget();
    }

    @Override
    public File deployedMappingsProvenanceFile() {
        return GeyserDeployer.ownershipFile();
    }

    @Override
    public void beginPublishedArtifactSetMutation(File outputDir) {
        BedrockOutputPublication.invalidateCache();
    }

    @Override
    public boolean commitPublishedArtifactSet(File outputDir) {
        return BedrockOutputPublication.publishCurrent(outputDir, this::isCancellationRequested);
    }

    @Override
    public void invalidatePublishedArtifactSet(File outputDir) {
        BedrockOutputPublication.withdraw(outputDir);
    }

    @Override
    public boolean withdrawPublishedArtifactSet(File outputDir) {
        return BedrockOutputPublication.revokeAuthority(outputDir);
    }

    @Override
    public void removeDeployedMappingsIfNeeded() {
        // Cleanup is authoritative even when auto-deploy was just disabled: a
        // mapping copied by the previous configuration must not survive forever.
        GeyserDeployer.removeMappings();
    }

    @Override
    public File previousMappingsFile() {
        File outputDir = new File(ResourcePackManager.plugin.getDataFolder(), "output");
        BedrockOutputPublication.Snapshot publication = BedrockOutputPublication.current(outputDir);
        return publication == null ? null : publication.mappings();
    }
}
