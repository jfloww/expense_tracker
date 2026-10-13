package com.expensetracker.accounts.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.expensetracker.accounts.domain.Account;
import com.expensetracker.accounts.domain.AccountIssuer;
import com.expensetracker.accounts.domain.AccountKind;
import com.expensetracker.accounts.persistence.AccountRepository;
import com.expensetracker.households.application.HouseholdAccess;
import com.expensetracker.identity.application.Actor;
import com.expensetracker.shared.error.DomainException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountQueryServiceTest {
    @Mock
    private HouseholdAccess householdAccess;

    @Mock
    private AccountRepository accounts;

    private AccountQueryService queries;
    private final Actor actor = new Actor(UUID.randomUUID());
    private final UUID householdId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        queries = new AccountQueryService(householdAccess, accounts);
    }

    @Test
    void checksCurrentMembershipBeforeScopedReadAndReturnsOnlyViews() {
        Account account = Account.create(householdId, "Synthetic account", AccountIssuer.CHASE,
                AccountKind.BANK_ACCOUNT, null, "USD", Instant.parse("2026-01-01T00:00:00Z"));
        when(accounts.findAllByHouseholdIdOrderByCreatedAtAscIdAsc(householdId))
                .thenReturn(List.of(account));

        List<AccountView> result = queries.list(actor, householdId);

        InOrder order = inOrder(householdAccess, accounts);
        order.verify(householdAccess).requireMember(actor, householdId);
        order.verify(accounts).findAllByHouseholdIdOrderByCreatedAtAscIdAsc(householdId);
        assertEquals(List.of(AccountView.from(account)), result);
    }

    @Test
    void deniedMembershipNeverFetchesPrivateAccounts() {
        when(householdAccess.requireMember(actor, householdId))
                .thenThrow(DomainException.forbidden());

        assertThrows(DomainException.class, () -> queries.list(actor, householdId));
        verifyNoInteractions(accounts);
    }

    @Test
    void foreignAccountIdIsNotResolvedGlobally() {
        UUID foreignAccountId = UUID.randomUUID();
        when(accounts.findByIdAndHouseholdId(foreignAccountId, householdId))
                .thenReturn(Optional.empty());

        assertThrows(DomainException.class, () -> queries.get(actor, householdId, foreignAccountId));
        InOrder order = inOrder(householdAccess, accounts);
        order.verify(householdAccess).requireMember(actor, householdId);
        order.verify(accounts).findByIdAndHouseholdId(foreignAccountId, householdId);
        order.verifyNoMoreInteractions();
    }
}
