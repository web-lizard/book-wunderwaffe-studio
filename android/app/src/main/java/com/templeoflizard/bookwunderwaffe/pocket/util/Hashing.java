package com.templeoflizard.bookwunderwaffe.pocket.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Pure-Java SHA-256 helpers shared by narration checkpoints and portable packages. */
public final class Hashing {
    private static final int BUFFER_SIZE = 64 * 1024;

    private Hashing() {}

    public static String sha256(String value) {
        return sha256(value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] bytes) {
        MessageDigest digest = newSha256Digest();
        digest.update(bytes == null ? new byte[0] : bytes);
        return toHex(digest.digest());
    }

    /** Reads but does not close the supplied stream. */
    public static String sha256(InputStream input) throws IOException {
        MessageDigest digest = newSha256Digest();
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read > 0) digest.update(buffer, 0, read);
        }
        return toHex(digest.digest());
    }

    public static MessageDigest newSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("Every supported Android runtime provides SHA-256", impossible);
        }
    }

    public static String toHex(byte[] bytes) {
        if (bytes == null) return "";
        char[] result = new char[bytes.length * 2];
        char[] alphabet = "0123456789abcdef".toCharArray();
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            result[index * 2] = alphabet[value >>> 4];
            result[index * 2 + 1] = alphabet[value & 0x0f];
        }
        return new String(result);
    }

    public static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) return false;
        byte[] a = left.getBytes(StandardCharsets.US_ASCII);
        byte[] b = right.getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(a, b);
    }
}
