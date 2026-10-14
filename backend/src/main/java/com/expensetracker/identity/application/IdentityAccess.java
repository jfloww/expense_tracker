package com.expensetracker.identity.application;

import com.expensetracker.identity.domain.User;
import com.expensetracker.identity.persistence.UserRepository;
import com.expensetracker.shared.error.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Internal identity contract for authorization and managed relationship references, never transport. */
@Service
public class IdentityAccess {
    private final UserRepository users;

    public IdentityAccess(UserRepository users) {
        this.users = users;
    }

    @Transactional(readOnly = true)
    public User requireEnabledUser(Actor actor) {
        // Check the database rather than trusting an enabled flag already loaded in a persistence context.
        if (actor == null || !users.existsByIdAndEnabledTrue(actor.userId())) {
            throw DomainException.forbidden();
        }
        return users.findById(actor.userId()).orElseThrow(DomainException::forbidden);
    }
}
