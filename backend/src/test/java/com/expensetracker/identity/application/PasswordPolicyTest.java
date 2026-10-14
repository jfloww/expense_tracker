package com.expensetracker.identity.application;

import com.expensetracker.shared.error.DomainException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PasswordPolicyTest {
    @Test void enforcesUnicodeLengthAndBcryptByteBoundsWithoutTrimming() {
        assertThrows(DomainException.class, () -> PasswordPolicy.validate("short-pass"));
        assertThrows(DomainException.class, () -> PasswordPolicy.validate(" ".repeat(20)));
        assertThrows(DomainException.class, () -> PasswordPolicy.validate("x".repeat(73)));
        assertThrows(DomainException.class, () -> PasswordPolicy.validate("한".repeat(25)));
        assertThrows(DomainException.class, () -> PasswordPolicy.validate("x".repeat(20) + '\0'));
        assertDoesNotThrow(() -> PasswordPolicy.validate("한".repeat(24)));
        assertDoesNotThrow(() -> PasswordPolicy.validate("  long-passphrase  "));
        assertFalse(PasswordPolicy.bounded(null));
    }
}
