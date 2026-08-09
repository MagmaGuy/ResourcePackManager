package com.magmaguy.resourcepackmanager.http;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Derives narrowly scoped bearer tokens from the shared RSPM network key.
 *
 * <p>The raw Floodgate-derived network key is never sent over HTTP. Each
 * protected capability gets a distinct domain string, so possession of a token
 * for one route does not authorize another route or reveal the network key.</p>
 */
public final class NetworkAccessToken {
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private NetworkAccessToken() {
    }

    public static String derive(String domain, String networkKey) {
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException("token domain must not be blank");
        }
        if (networkKey == null || networkKey.isBlank()) {
            throw new IllegalArgumentException("network key must not be blank");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(networkKey.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] token = mac.doFinal(domain.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(HMAC_ALGORITHM + " is unavailable", impossible);
        } catch (java.security.InvalidKeyException exception) {
            throw new IllegalArgumentException("network key could not initialize the access token", exception);
        }
    }

    public static String authorizationValue(String domain, String networkKey) {
        return "Bearer " + derive(domain, networkKey);
    }

    public static boolean matchesAuthorization(String authorization,
                                               String domain,
                                               String networkKey) {
        if (authorization == null || authorization.length() <= 7
                || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return false;
        }
        String candidate = authorization.substring(7).trim();
        if (candidate.isEmpty() || candidate.indexOf(' ') >= 0) {
            return false;
        }
        String expected;
        try {
            expected = derive(domain, networkKey);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                candidate.getBytes(StandardCharsets.US_ASCII));
    }
}
