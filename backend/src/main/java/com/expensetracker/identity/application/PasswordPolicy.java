package com.expensetracker.identity.application;

import com.expensetracker.shared.error.DomainException;
import java.nio.charset.StandardCharsets;

/** BCrypt's byte limit is checked before hashing; passwords are never trimmed. */
public final class PasswordPolicy {
    private PasswordPolicy() {}

    public static void validate(String password) {
        if (password == null || password.codePointCount(0, password.length()) < 12
                || !bounded(password) || password.isBlank()) {
            throw DomainException.invalid("invalid_password");
        }
    }

    public static boolean bounded(String password) {
        return password != null && password.length() <= 72 && password.indexOf('\0') < 0
                && password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}
