package com.expensetracker.identity.application;

import com.expensetracker.identity.domain.User;
import com.expensetracker.identity.persistence.UserRepository;
import com.expensetracker.shared.error.DomainException;
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

    /** Registration domain rules also apply to HTTP and internal callers. */
    @Transactional
    public UserView register(String email, String displayName, String password) {
        String normalizedEmail = User.normalizeEmail(email);
        String normalizedName = User.validateDisplayName(displayName);
        PasswordPolicy.validate(password);
        if (users.findByEmail(normalizedEmail).isPresent()) {
            throw DomainException.conflict("email_already_registered");
        }
        User user = users.saveAndFlush(User.create(normalizedEmail, normalizedName, passwords.encode(password)));
        return new UserView(user.getId(), user.getEmail(), user.getDisplayName(), user.getCreatedAt());
    }
}
