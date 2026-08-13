package com.magmaguy.resourcepackmanager.http;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

/**
 * Authenticates a network-key grant using Velocity's modern-forwarding secret.
 *
 * <p>The grant travels the proxy→backend connection, so it is already confined to a link
 * the network trusts for player forwarding. Where a stronger anchor exists, use it: a
 * Velocity network running modern forwarding <em>must</em> hold the same secret on the
 * proxy and on every backend, or players cannot connect at all. That makes it a free,
 * already-shared signing key — nothing new for an operator to configure or leak.</p>
 *
 * <p>The secret itself is never transmitted; only an HMAC over the granted key.</p>
 *
 * <p>Signing is best-effort by necessity — BungeeCord and Waterfall have no equivalent
 * secret ({@code ip_forward} is a plain boolean), and Velocity's legacy forwarding mode
 * doesn't use one either. The downgrade is closed at the <em>verifying</em> end instead:
 * a backend that holds a secret demands a valid signature, so an attacker cannot strip
 * the signature to bypass the check. A backend with no secret accepts an unsigned grant,
 * because on that topology nothing better exists.</p>
 */
public final class NetworkKeyGrantSignature {

    private static final String ALGORITHM = "HmacSHA256";

    private NetworkKeyGrantSignature() {
    }

    /**
     * @return hex-encoded HMAC of {@code networkKey} under {@code secret}, or {@code null}
     *         when there is no secret to sign with
     */
    public static String sign(String secret, String networkKey) {
        if (secret == null || secret.isBlank() || networkKey == null) return null;
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] out = mac.doFinal(networkKey.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(out.length * 2);
            for (byte b : out) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception exception) {
            return null;
        }
    }

    /**
     * Constant-time comparison of a presented signature against the expected one.
     *
     * @return {@code true} when {@code presented} is a valid signature of {@code networkKey}
     */
    public static boolean verify(String secret, String networkKey, String presented) {
        if (presented == null || presented.isBlank()) return false;
        String expected = sign(secret, networkKey);
        if (expected == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Reads Velocity's forwarding secret from the proxy's secret file.
     *
     * @return the secret, or {@code null} when the file is absent or empty — a normal
     *         state on legacy forwarding and on Bungee/Waterfall
     */
    public static String readProxyForwardingSecret(Path secretFile) {
        if (secretFile == null) return null;
        try {
            if (!Files.isRegularFile(secretFile)) return null;
            String raw = new String(Files.readAllBytes(secretFile), StandardCharsets.UTF_8).trim();
            return raw.isEmpty() ? null : raw;
        } catch (Exception exception) {
            return null;
        }
    }
}
