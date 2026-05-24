package com.magmaguy.rspm.mixer.internal;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * SHA-1 helpers for the mixer module. Mirrors the format produced by the older
 * {@code com.magmaguy.resourcepackmanager.utils.SHA1Generator} (uppercase hex string,
 * zero-padded) so existing consumers — most notably {@code AutoHost.sendResourcePack}
 * which forwards the hex to Bukkit's resource-pack APIs — see byte-identical output.
 */
public final class Sha1 {
    private Sha1() {
    }

    public static byte[] bytes(File file) throws IOException {
        try (FileInputStream fileInputStream = new FileInputStream(file);
             DigestInputStream digestInputStream = new DigestInputStream(fileInputStream, MessageDigest.getInstance("SHA-1"))) {
            byte[] buffer = new byte[1024];
            while (digestInputStream.read(buffer) > 0) {
                // streaming digest update
            }
            return digestInputStream.getMessageDigest().digest();
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 is mandated by the JDK spec; treat absence as an I/O-level failure.
            throw new IOException("SHA-1 algorithm not available", e);
        }
    }

    public static String hex(File file) throws IOException {
        return bytesToHexString(bytes(file));
    }

    public static String bytesToHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int value = b & 0xFF;
            if (value < 16) {
                sb.append('0');
            }
            sb.append(Integer.toHexString(value).toUpperCase(Locale.ROOT));
        }
        return sb.toString();
    }
}
