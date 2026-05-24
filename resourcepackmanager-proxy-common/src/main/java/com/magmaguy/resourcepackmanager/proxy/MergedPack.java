package com.magmaguy.resourcepackmanager.proxy;

import java.util.UUID;

/**
 * Snapshot of the most recent network-merged resource pack the proxy has
 * published. Consumers (the Java pack-push code and the Bedrock/Geyser binder)
 * read this whenever they need to register or send the pack to a client.
 *
 * @param url       publicly reachable URL the client should fetch the pack from
 *                  (either a magmaguy.com upload URL or the proxy's self-host URL)
 * @param sha1Hex   lowercase hex SHA-1 of the merged zip
 * @param sha1Bytes raw SHA-1 bytes (20 bytes) — convenient for protocol APIs that
 *                  take {@code byte[]} rather than hex
 * @param packUuid  UUID announced to the client; stable across re-mixes so
 *                  1.20.3+ clients treat repeated sends as no-ops
 */
public record MergedPack(
        String url,
        String sha1Hex,
        byte[] sha1Bytes,
        UUID packUuid
) {
    /**
     * Stable UUID identifying RPM's network-merged pack across re-mixes. 1.20.3+
     * clients treat repeated sends of the same UUID as a no-op, so admins don't
     * see pack re-prompts when the proxy republishes the merged pack after a
     * backend's contribution changes.
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
