package com.magmaguy.resourcepackmanager.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Grant signing is the only cryptographic step in provisioning, and its
 * failure modes are exactly the attacks: a stripped signature must not verify
 * on a backend that holds a secret, and a signature under the wrong secret
 * must never pass. The no-secret cases are equally load-bearing — Bungee and
 * Waterfall have nothing to sign with, and that must degrade to "unsigned",
 * never to an exception or a bogus signature.
 */
class NetworkKeyGrantSignatureTest {

    private static final String SECRET = "forwarding-secret-fixture";
    private static final String KEY = "7c5ddcfd-34ec-8b2f-cc78-64cef89b0a18";

    @Test
    void signIsDeterministicHexAndSecretBound() {
        String first = NetworkKeyGrantSignature.sign(SECRET, KEY);
        String second = NetworkKeyGrantSignature.sign(SECRET, KEY);

        assertEquals(first, second);
        assertTrue(first.matches("[0-9a-f]{64}"));
        assertNotEquals(first, NetworkKeyGrantSignature.sign("other-secret", KEY));
        assertNotEquals(first, NetworkKeyGrantSignature.sign(SECRET, "other-key"));
    }

    @Test
    void signWithoutASecretYieldsNullNotAnException() {
        assertNull(NetworkKeyGrantSignature.sign(null, KEY));
        assertNull(NetworkKeyGrantSignature.sign("   ", KEY));
        assertNull(NetworkKeyGrantSignature.sign(SECRET, null));
    }

    @Test
    void verifyAcceptsOnlyTheMatchingSignature() {
        String signature = NetworkKeyGrantSignature.sign(SECRET, KEY);

        assertTrue(NetworkKeyGrantSignature.verify(SECRET, KEY, signature));
        assertFalse(NetworkKeyGrantSignature.verify(SECRET, "other-key", signature));
        assertFalse(NetworkKeyGrantSignature.verify("other-secret", KEY, signature));
        assertFalse(NetworkKeyGrantSignature.verify(SECRET, KEY,
                NetworkKeyGrantSignature.sign("other-secret", KEY)));
    }

    /**
     * The downgrade attack in one assertion: a backend holding a secret is
     * offered an unsigned grant. Accepting it would make the whole signing
     * scheme decorative.
     */
    @Test
    void verifyRejectsAbsentOrBlankSignatures() {
        assertFalse(NetworkKeyGrantSignature.verify(SECRET, KEY, null));
        assertFalse(NetworkKeyGrantSignature.verify(SECRET, KEY, ""));
        assertFalse(NetworkKeyGrantSignature.verify(SECRET, KEY, "   "));
    }

    @Test
    void verifyWithoutASecretNeverPasses() {
        String signature = NetworkKeyGrantSignature.sign(SECRET, KEY);
        assertFalse(NetworkKeyGrantSignature.verify(null, KEY, signature));
        assertFalse(NetworkKeyGrantSignature.verify("", KEY, signature));
    }

    @Test
    void readsTrimmedSecretAndTreatsAbsentOrEmptyAsNone(@TempDir Path dir) throws Exception {
        Path secretFile = dir.resolve("forwarding.secret");
        Files.writeString(secretFile, "  " + SECRET + "\n");
        assertEquals(SECRET, NetworkKeyGrantSignature.readProxyForwardingSecret(secretFile));

        Files.writeString(secretFile, "\n   \n");
        assertNull(NetworkKeyGrantSignature.readProxyForwardingSecret(secretFile));

        assertNull(NetworkKeyGrantSignature.readProxyForwardingSecret(dir.resolve("absent")));
        assertNull(NetworkKeyGrantSignature.readProxyForwardingSecret(null));
    }
}
