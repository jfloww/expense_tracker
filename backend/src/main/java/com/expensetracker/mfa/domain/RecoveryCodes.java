package com.expensetracker.mfa.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

/** High-entropy bearer codes; only the user-bound digest may be persisted. */
public final class RecoveryCodes {
    private static final SecureRandom RANDOM = new SecureRandom();

    private RecoveryCodes() {}

    public static List<String> generate() {
        return IntStream.range(0, 10).mapToObj(ignored -> {
            byte[] bytes = new byte[16];
            RANDOM.nextBytes(bytes);
            return HexFormat.of().formatHex(bytes);
        }).toList();
    }

    public static boolean validFormat(String code) {
        return code != null && code.matches("[a-f0-9]{32}");
    }

    public static String hash(UUID userId, String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    (userId + ":" + code).getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("Required recovery-code primitive unavailable", error);
        }
    }
}
