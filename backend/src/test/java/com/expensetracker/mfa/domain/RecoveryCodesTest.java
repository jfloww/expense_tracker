package com.expensetracker.mfa.domain;

import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryCodesTest {
    @Test
    void createsTenIndependentOneHundredTwentyEightBitCodesWithUserBoundDigests() {
        var codes = RecoveryCodes.generate();
        assertEquals(10, codes.size());
        assertEquals(10, new HashSet<>(codes).size());
        UUID userId = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        for (String code : codes) {
            assertTrue(code.matches("[a-f0-9]{32}"));
            assertTrue(RecoveryCodes.validFormat(code));
            String digest = RecoveryCodes.hash(userId, code);
            assertTrue(digest.matches("[a-f0-9]{64}"));
            assertNotEquals(digest, RecoveryCodes.hash(otherUser, code));
        }
        assertFalse(RecoveryCodes.validFormat(null));
        assertFalse(RecoveryCodes.validFormat("A".repeat(32)));
        assertFalse(RecoveryCodes.validFormat("0".repeat(31)));
    }
}
