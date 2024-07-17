package com.magmaguy.resourcepackmanager.utils;

import com.magmaguy.resourcepackmanager.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

public class SHA1Generator {
    public static String sha1CodeString(File file) throws IOException, NoSuchAlgorithmException {
        try (FileInputStream fileInputStream = new FileInputStream(file);
             DigestInputStream digestInputStream = new DigestInputStream(fileInputStream, MessageDigest.getInstance("SHA-1"))) {
            byte[] bytes = new byte[1024];
            MessageDigest digest = null;
            //read all file content
            while (digestInputStream.read(bytes) > 0) digest = digestInputStream.getMessageDigest();
            byte[] resultByteArry = digest.digest();
            return bytesToHexString(resultByteArry);
        }
    }

    public static String sha1CodeString(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            byte[] resultByteArry = digest.digest(bytes);
            return bytesToHexString(resultByteArry);
        } catch (NoSuchAlgorithmException e) {
            Logger.warn("Failed to find SHA-1 algorithm.");
        }
        return null;
    }

    public static byte[] sha1CodeByteArray(File file) throws IOException, NoSuchAlgorithmException {
        FileInputStream fileInputStream = new FileInputStream(file);
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        DigestInputStream digestInputStream = new DigestInputStream(fileInputStream, digest);
        byte[] bytes = new byte[1024];
        // read all file content
        while (digestInputStream.read(bytes) > 0) digest = digestInputStream.getMessageDigest();
        return digest.digest();
    }

    public static String bytesToHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            int value = b & 0xFF;
            if (value < 16) {
                // if value less than 16, then it's hex String will be only
                // one character, so we need to append a character of '0'
                sb.append("0");
            }
            sb.append(Integer.toHexString(value).toUpperCase(Locale.ROOT));
        }
        return sb.toString();
    }
}