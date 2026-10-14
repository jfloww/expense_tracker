package com.expensetracker.identity.security;

import com.expensetracker.identity.persistence.UserRepository;
import com.expensetracker.shared.error.DomainException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Credential adapter used by the explicit Spring Security session login flow. */
@Service
public class DatabaseUserDetailsService implements UserDetailsService {
    private final UserRepository users;

    public DatabaseUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) {
        String normalized;
        try {
            normalized = com.expensetracker.identity.domain.User.normalizeEmail(email);
        } catch (DomainException invalidEmail) {
            throw new UsernameNotFoundException("User not found");
        }
        var user = users.findByEmail(normalized)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
        return org.springframework.security.core.userdetails.User.withUsername(user.getEmail())
                .password(user.getPasswordHash())
                .disabled(!user.isEnabled() || !user.isPasswordLoginEnabled())
                .authorities("USER")
                .build();
    }
}
