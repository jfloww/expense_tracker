package com.expensetracker.mfa.security;

import com.expensetracker.mfa.application.Enrollment;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MfaSecretCipherTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    void encryptsWithUniqueNoncesAndAuthenticatesUserAndCiphertext() {
        var cipher = new MfaSecretCipher(KEY);
        UUID userId = UUID.randomUUID();
        String encrypted = cipher.encrypt(userId, SECRET);
        assertEquals(SECRET, cipher.decrypt(userId, encrypted));
        assertNotEquals(encrypted, cipher.encrypt(userId, SECRET));
        assertFalse(encrypted.contains(SECRET));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(UUID.randomUUID(), encrypted));
        byte[] tampered = Base64.getDecoder().decode(encrypted);
        tampered[tampered.length - 1] ^= 1;
        assertThrows(IllegalStateException.class,
                () -> cipher.decrypt(userId, Base64.getEncoder().encodeToString(tampered)));
        byte[] anotherKey = new byte[32];
        anotherKey[0] = 1;
        var wrongKey = new MfaSecretCipher(Base64.getEncoder().encodeToString(anotherKey));
        assertThrows(IllegalStateException.class, () -> wrongKey.decrypt(userId, encrypted));
    }

    @Test
    void requiresConfiguredCanonicalThirtyTwoByteKey() {
        for (String invalid : new String[]{"", "not-base64", Base64.getEncoder().encodeToString(new byte[31]),
                Base64.getEncoder().encodeToString(new byte[33]), KEY.substring(0, KEY.length() - 1)}) {
            assertThrows(IllegalArgumentException.class, () -> new MfaSecretCipher(invalid));
        }
    }

    @Test
    void enrollmentDiagnosticsRedactBothSecretAndProvisioningUri() {
        var enrollment = new Enrollment(SECRET, "otpauth://totp/synthetic?secret=" + SECRET, Instant.EPOCH);
        assertFalse(enrollment.toString().contains(SECRET));
        assertFalse(enrollment.toString().contains("otpauth://"));
    }
}
