package com.magmaguy.resourcepackmanager.http;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Stable per-backend identifier used as the {@code backendId} field when
 * uploading to and downloading from the Bedrock pack relay.
 *
 * <p>Each backend instance writes a UUID to
 * {@code plugins/ResourcePackManager/backend-id.txt} on first run. Subsequent
 * runs read the same file. The proxy doesn't need to know any backend's ID in
 * advance — it asks the relay {@code POST /bedrock/list} for all live entries
 * on its network and merges everything it finds. Each backend therefore
 * appears as its own namespace under the network's relay storage, with no
 * collision possible even if backends share an MC port across hosts.</p>
 *
 * <p>The file format is a single UUID string with optional trailing newline.
 * If the file is corrupted (empty, parse fails), a fresh UUID is generated
 * and the file is rewritten — the previous relay entries under the old ID
 * will TTL-expire on the hoster within minutes.</p>
 */
public final class BackendIdentity {

    private BackendIdentity() {}

    /**
     * Load the existing backend ID from {@code file}, or create a new one and
     * persist it if the file is absent / corrupt. Never returns null — falls
     * through to an in-memory UUID on persistent I/O failure (which means
     * relay entries from this session won't survive restart, but the
     * RSPM session can still function).
     *
     * @param file the storage path (typically
     *             {@code plugins/ResourcePackManager/backend-id.txt})
     */
    public static String loadOrCreate(Path file) {
        if (file == null) return UUID.randomUUID().toString();
        try {
            if (Files.isRegularFile(file)) {
                String existing = Files.readString(file).trim();
                if (looksLikeUuid(existing)) return existing;
                // Corrupt — fall through to regenerate.
            }
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            String fresh = UUID.randomUUID().toString();
            Files.writeString(file, fresh);
            return fresh;
        } catch (IOException e) {
            // Disk full / permissions / read-only FS — give the caller a UUID
            // anyway. They'll re-roll on next start (the old ID's relay
            // entries will TTL-expire), which is acceptable degraded mode.
            return UUID.randomUUID().toString();
        }
    }

    private static boolean looksLikeUuid(String s) {
        if (s == null || s.length() != 36) return false;
        try {
            UUID.fromString(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
