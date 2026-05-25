package com.magmaguy.resourcepackmanager.proxy;

import java.io.File;
import java.util.UUID;

/**
 * Snapshot of the most recent network-merged Bedrock resource pack the proxy has
 * published. Consumers (the Bedrock/Geyser binder) read this whenever they need
 * to register the pack with a Bedrock session.
 *
 * <p>The proxy no longer self-hosts a URL — it hands the merged Bedrock zip
 * directly to Geyser via {@code PackCodec.path(packFile.toPath())}, and Geyser
 * itself serves the bytes to Bedrock clients over the Bedrock protocol. Hence
 * no {@code url} field on this record.
 *
 * @param packFile  on-disk path to the merged Bedrock zip; stable across re-merges
 *                  (overwritten atomically by {@link NetworkSync})
 * @param sha1Hex   lowercase hex SHA-1 of the merged zip
 * @param sha1Bytes raw SHA-1 bytes (20 bytes) — convenient for protocol APIs that
 *                  take {@code byte[]} rather than hex
 * @param packUuid  UUID announced to the client; stable across re-merges so
 *                  Bedrock clients treat repeated sends as no-ops
 */
public record MergedPack(
        File packFile,
        String sha1Hex,
        byte[] sha1Bytes,
        UUID packUuid
) {
    /**
     * Stable UUID identifying RPM's network-merged pack across re-merges. Bedrock
     * clients cache pack bytes by UUID, so keeping this stable means a player
     * who already has the previous merge cached doesn't re-download the whole
     * pack just because one backend's contribution changed.
     *
     * <p>Distinct from {@code AutoHost.RESOURCE_PACK_UUID}
     * ({@code a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d}), which identifies the
     * single-server (non-network) pack pushed by the Bukkit plugin. Keeping
     * these UUIDs separate means a player who has both a backend's local pack
     * and the proxy's network pack cached won't have one overwrite the other in
     * the client cache.</p>
     *
     * <p>Important: do not change this value once shipped — clients cache pack
     * bytes by UUID. Changing it would force every player on the network to
     * re-download their cached copy.</p>
     */
    public static final UUID NETWORK_PACK_UUID = UUID.fromString("b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e");
}
