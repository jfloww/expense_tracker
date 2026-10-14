package com.expensetracker.mfa.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 6238 SHA-1, six digits, 30-second steps; callers persist the accepted step. */
public final class Totp {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {}

    public static String newSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        try {
            return encodeBase32(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    public static String codeAt(String secret, Instant instant) {
        return codeForStep(secret, Math.floorDiv(instant.getEpochSecond(), 30));
    }

    public static OptionalLong match(String secret, String code, Instant now, long lastUsedStep) {
        if (code == null || !code.matches("[0-9]{6}")) {
            return OptionalLong.empty();
        }
        long current = Math.floorDiv(now.getEpochSecond(), 30);
        byte[] supplied = code.getBytes(StandardCharsets.US_ASCII);
        long accepted = -1;
        // Compare every permitted step; prefer the newest in the rare event of a collision.
        for (long candidate = current - 1; candidate <= current + 1; candidate++) {
            if (candidate < 0) {
                continue;
            }
            boolean equal = MessageDigest.isEqual(supplied,
                    codeForStep(secret, candidate).getBytes(StandardCharsets.US_ASCII));
            if (equal && candidate > lastUsedStep) {
                accepted = candidate;
            }
        }
        return accepted >= 0 ? OptionalLong.of(accepted) : OptionalLong.empty();
    }

    private static String codeForStep(String secret, long step) {
        if (step < 0) {
            throw new IllegalArgumentException("TOTP requires a nonnegative timestep");
        }
        byte[] key = decodeBase32(secret);
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] digest = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
            int offset = digest[digest.length - 1] & 15;
            int truncated = ((digest[offset] & 127) << 24)
                    | ((digest[offset + 1] & 255) << 16)
                    | ((digest[offset + 2] & 255) << 8)
                    | (digest[offset + 3] & 255);
            return String.format(Locale.ROOT, "%06d", truncated % 1_000_000);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Required TOTP primitive unavailable", error);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private static String encodeBase32(byte[] bytes) {
        StringBuilder encoded = new StringBuilder(32);
        int buffer = 0;
        int bits = 0;
        for (byte value : bytes) {
            buffer = (buffer << 8) | (value & 255);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                encoded.append(ALPHABET.charAt((buffer >>> bits) & 31));
            }
        }
        if (bits > 0) {
            encoded.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        }
        return encoded.toString();
    }

    private static byte[] decodeBase32(String secret) {
        if (secret == null || !secret.matches("[A-Z2-7]{32}")) {
            throw new IllegalArgumentException("Invalid TOTP secret format");
        }
        byte[] result = new byte[20];
        int buffer = 0;
        int bits = 0;
        int index = 0;
        for (char value : secret.toCharArray()) {
            buffer = (buffer << 5) | ALPHABET.indexOf(value);
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                result[index++] = (byte) (buffer >>> bits);
            }
        }
        return result;
    }
}
