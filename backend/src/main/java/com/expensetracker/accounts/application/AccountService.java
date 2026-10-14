package com.expensetracker.accounts.application;

import com.expensetracker.accounts.domain.Account;
import com.expensetracker.accounts.persistence.AccountRepository;
import com.expensetracker.households.application.HouseholdAccess;
import com.expensetracker.identity.application.Actor;
import com.expensetracker.shared.error.DomainException;
import jakarta.validation.Validator;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
    private final HouseholdAccess householdAccess;
    private final AccountRepository accounts;
    private final Validator validator;

    public AccountService(HouseholdAccess householdAccess, AccountRepository accounts,
                          Validator validator) {
        this.householdAccess = householdAccess;
        this.accounts = accounts;
        this.validator = validator;
    }

    @Transactional
    public AccountView create(Actor actor, UUID householdId, CreateAccountCommand command) {
        householdAccess.lockAndRequireMember(actor, householdId);
        validate(command);
        Account account = Account.create(householdId, command.name(), command.issuer(),
                command.kind(), command.lastFour(), command.currency(), Instant.now());
        accounts.save(account);
        return AccountView.from(account);
    }

    @Transactional
    public AccountView update(Actor actor, UUID householdId, UUID accountId,
                              UpdateAccountCommand command) {
        householdAccess.lockAndRequireMember(actor, householdId);
        validate(command);
        Account account = accounts.findByIdAndHouseholdId(accountId, householdId)
                .orElseThrow(DomainException::notFound);
        account.update(command.name(), command.issuer(), command.kind(), command.lastFour(),
                command.currency(), command.active(), Instant.now());
        return AccountView.from(account);
    }

    private void validate(Object command) {
        if (command == null || !validator.validate(command).isEmpty()) {
            throw DomainException.invalid("invalid_account");
        }
    }
}
