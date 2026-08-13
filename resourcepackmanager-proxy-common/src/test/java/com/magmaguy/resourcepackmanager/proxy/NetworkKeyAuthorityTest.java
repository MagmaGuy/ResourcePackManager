package com.magmaguy.resourcepackmanager.proxy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The proxy's key resolution order is a compatibility contract: existing
 * networks derive their identity from Floodgate's {@code key.pem}, and an
 * upgrade that changes their key silently unlinks every backend and orphans
 * their relay tenancy. These tests pin each branch of
 * persisted → seeded → minted, and prove the seed reproduces the legacy
 * derivation exactly rather than merely "some stable value".
 */
class NetworkKeyAuthorityTest {

    @Test
    void persistedKeyWinsOverSeedAndIsReportedAsPersisted(@TempDir Path dataDir) throws Exception {
        String existing = "11111111-2222-3333-4444-555555555555";
        Files.writeString(dataDir.resolve(NetworkKeyAuthority.KEY_FILENAME), existing + "\n");
        Path keyPem = dataDir.resolve("key.pem");
        Files.writeString(keyPem, "a floodgate key that must be ignored");

        NetworkKeyAuthority.Resolution resolution = NetworkKeyAuthority.resolve(dataDir, keyPem);

        assertEquals(existing, resolution.key());
        assertEquals(NetworkKeyAuthority.Source.PERSISTED, resolution.source());
        assertTrue(resolution.persisted());
    }

    /**
     * The migration guarantee itself: seeding must equal the legacy scheme,
     * UUID(first 16 bytes of SHA-256(key.pem bytes)), computed here
     * independently so a rewrite of the production code cannot silently
     * redefine the identity it claims to preserve.
     */
    @Test
    void seedReproducesLegacyDerivationByteForByte(@TempDir Path dataDir) throws Exception {
        Path keyPem = dataDir.resolve("key.pem");
        byte[] pemBytes = "shared floodgate private key fixture".getBytes(StandardCharsets.UTF_8);
        Files.write(keyPem, pemBytes);

        byte[] hash = MessageDigest.getInstance("SHA-256").digest(pemBytes);
        long msb = 0L;
        long lsb = 0L;
        for (int i = 0; i < 8; i++) msb = (msb << 8) | (hash[i] & 0xff);
        for (int i = 8; i < 16; i++) lsb = (lsb << 8) | (hash[i] & 0xff);
        String legacy = new UUID(msb, lsb).toString();

        NetworkKeyAuthority.Resolution resolution = NetworkKeyAuthority.resolve(dataDir, keyPem);

        assertEquals(legacy, resolution.key());
        assertEquals(NetworkKeyAuthority.Source.SEEDED_FROM_FLOODGATE, resolution.source());
        assertTrue(resolution.persisted());
        assertEquals(legacy,
                Files.readString(dataDir.resolve(NetworkKeyAuthority.KEY_FILENAME)).trim());
    }

    @Test
    void mintsWhenNothingExistsAndPersistsForTheNextBoot(@TempDir Path dataDir) {
        NetworkKeyAuthority.Resolution first =
                NetworkKeyAuthority.resolve(dataDir, dataDir.resolve("absent-key.pem"));

        assertEquals(NetworkKeyAuthority.Source.MINTED, first.source());
        assertTrue(first.persisted());
        assertNotNull(first.key());
        assertEquals(first.key(), UUID.fromString(first.key()).toString());

        NetworkKeyAuthority.Resolution second =
                NetworkKeyAuthority.resolve(dataDir, dataDir.resolve("absent-key.pem"));
        assertEquals(NetworkKeyAuthority.Source.PERSISTED, second.source());
        assertEquals(first.key(), second.key());
    }

    /**
     * A truncated or corrupted key file must be re-established, not
     * propagated: handing a malformed value to every backend would poison the
     * whole network with an identity nothing else can reproduce.
     */
    @Test
    void malformedPersistedKeyIsReplacedInsteadOfPropagated(@TempDir Path dataDir) throws Exception {
        Files.writeString(dataDir.resolve(NetworkKeyAuthority.KEY_FILENAME), "not-a-uuid");

        NetworkKeyAuthority.Resolution resolution =
                NetworkKeyAuthority.resolve(dataDir, dataDir.resolve("absent-key.pem"));

        assertEquals(NetworkKeyAuthority.Source.MINTED, resolution.source());
        assertNotEquals("not-a-uuid", resolution.key());
        assertEquals(resolution.key(),
                Files.readString(dataDir.resolve(NetworkKeyAuthority.KEY_FILENAME)).trim());
    }

    /**
     * When the key cannot be written the resolution must say so — an
     * unpersisted key re-mints on the next boot, silently unlinking every
     * backend provisioned with this one, so callers escalate loudly.
     */
    @Test
    void reportsPersistenceFailureInsteadOfPretending(@TempDir Path parent) throws Exception {
        Path fileAsDataDir = parent.resolve("actually-a-file");
        Files.writeString(fileAsDataDir, "occupies the dataDir path");

        NetworkKeyAuthority.Resolution resolution =
                NetworkKeyAuthority.resolve(fileAsDataDir, parent.resolve("absent-key.pem"));

        assertEquals(NetworkKeyAuthority.Source.MINTED, resolution.source());
        assertFalse(resolution.persisted());
        assertNotNull(resolution.persistenceError());
    }
}
