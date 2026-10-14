package com.expensetracker.mfa.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** AES-256-GCM with a fresh 96-bit nonce and immutable user identity as AAD. */
@Component
public final class MfaSecretCipher {
    private static final int NONCE_BYTES = 12;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public MfaSecretCipher(@Value("${security.mfa.encryption-key:}") String encodedKey) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("MFA encryption key must be Base64 for 32 bytes");
        }
        if (decoded.length != 32 || !Base64.getEncoder().encodeToString(decoded).equals(encodedKey)) {
            Arrays.fill(decoded, (byte) 0);
            throw new IllegalArgumentException("MFA encryption key must be Base64 for 32 bytes");
        }
        key = new SecretKeySpec(decoded, "AES");
        Arrays.fill(decoded, (byte) 0);
    }

    public String encrypt(UUID userId, String secret) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        byte[] plaintext = secret.getBytes(StandardCharsets.US_ASCII);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, userId, nonce);
            byte[] ciphertext = cipher.doFinal(plaintext);
            byte[] envelope = ByteBuffer.allocate(1 + nonce.length + ciphertext.length)
                    .put((byte) 1).put(nonce).put(ciphertext).array();
            return Base64.getEncoder().encodeToString(envelope);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("MFA secret encryption failed", error);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    public String decrypt(UUID userId, String envelope) {
        byte[] plaintext = null;
        try {
            byte[] bytes = Base64.getDecoder().decode(envelope);
            if (bytes.length < 1 + NONCE_BYTES + 16 || bytes[0] != 1) {
                throw new IllegalArgumentException("Invalid MFA ciphertext envelope");
            }
            byte[] nonce = Arrays.copyOfRange(bytes, 1, 1 + NONCE_BYTES);
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, userId, nonce);
            plaintext = cipher.doFinal(bytes, 1 + NONCE_BYTES, bytes.length - 1 - NONCE_BYTES);
            return new String(plaintext, StandardCharsets.US_ASCII);
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            throw new IllegalStateException("MFA secret decryption failed", error);
        } finally {
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    private Cipher cipher(int mode, UUID userId, byte[] nonce) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(("expense-tracker:mfa:" + userId).getBytes(StandardCharsets.US_ASCII));
        return cipher;
    }
}
