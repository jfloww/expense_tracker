package com.expensetracker.accounts.application;

import com.expensetracker.accounts.persistence.AccountRepository;
import com.expensetracker.households.application.HouseholdAccess;
import com.expensetracker.identity.application.Actor;
import com.expensetracker.shared.error.DomainException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AccountQueryService {
    private final HouseholdAccess householdAccess;
    private final AccountRepository accounts;

    public AccountQueryService(HouseholdAccess householdAccess, AccountRepository accounts) {
        this.householdAccess = householdAccess;
        this.accounts = accounts;
    }

    public List<AccountView> list(Actor actor, UUID householdId) {
        householdAccess.requireMember(actor, householdId);
        return accounts.findAllByHouseholdIdOrderByCreatedAtAscIdAsc(householdId)
                .stream().map(AccountView::from).toList();
    }

    public AccountView get(Actor actor, UUID householdId, UUID accountId) {
        householdAccess.requireMember(actor, householdId);
        return accounts.findByIdAndHouseholdId(accountId, householdId)
                .map(AccountView::from).orElseThrow(DomainException::notFound);
    }
}
