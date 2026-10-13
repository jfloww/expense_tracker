package com.expensetracker.identity.application;

import com.expensetracker.identity.domain.User;
import com.expensetracker.identity.persistence.UserRepository;
import com.expensetracker.shared.error.DomainException;
import java.nio.charset.StandardCharsets;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityService {
    private final UserRepository users;
    private final PasswordEncoder passwords;

    public IdentityService(UserRepository users, PasswordEncoder passwords) {
        this.users = users;
        this.passwords = passwords;
    }

    /** Internal bootstrap operation; public registration and its abuse controls come later. */
    @Transactional
    public UserView register(String email, String displayName, String password) {
        String normalizedEmail = User.normalizeEmail(email);
        String normalizedName = User.validateDisplayName(displayName);
        if (password == null) {
            throw DomainException.invalid("invalid_password");
        }
        int byteLength = password.getBytes(StandardCharsets.UTF_8).length;
        if (password.length() < 8 || byteLength > 72 || password.isBlank()) {
            throw DomainException.invalid("invalid_password");
        }
        if (users.findByEmail(normalizedEmail).isPresent()) {
            throw DomainException.conflict("email_already_registered");
        }
        User user = users.saveAndFlush(User.create(normalizedEmail, normalizedName, passwords.encode(password)));
        return new UserView(user.getId(), user.getEmail(), user.getDisplayName(), user.getCreatedAt());
    }
}
