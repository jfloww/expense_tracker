package com.expensetracker.accounts.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.expensetracker.accounts.domain.Account;
import com.expensetracker.accounts.domain.AccountIssuer;
import com.expensetracker.accounts.domain.AccountKind;
import com.expensetracker.accounts.persistence.AccountRepository;
import com.expensetracker.households.application.HouseholdAccess;
import com.expensetracker.identity.application.Actor;
import com.expensetracker.shared.error.DomainException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {
    private static ValidatorFactory validators;

    @Mock
    private HouseholdAccess householdAccess;

    @Mock
    private AccountRepository accounts;

    private AccountService service;
    private final Actor actor = new Actor(UUID.randomUUID());
    private final UUID householdId = UUID.randomUUID();

    @BeforeAll
    static void startValidation() {
        validators = Validation.buildDefaultValidatorFactory();
    }

    @AfterAll
    static void stopValidation() {
        validators.close();
    }

    @BeforeEach
    void setUp() {
        service = new AccountService(householdAccess, accounts, validators.getValidator());
    }

    @ParameterizedTest(name = "rejects invalid creation command {index}")
    @MethodSource("invalidCreationCommands")
    void rejectsInvalidCommandsForApplicationCallers(CreateAccountCommand command) {
        assertThrows(DomainException.class, () -> service.create(actor, householdId, command));
        verify(householdAccess).lockAndRequireMember(actor, householdId);
        verifyNoInteractions(accounts);
    }

    static Stream<CreateAccountCommand> invalidCreationCommands() {
        return Stream.of(
                null,
                new CreateAccountCommand(null, AccountIssuer.CHASE, AccountKind.CREDIT_CARD, null, "USD"),
                new CreateAccountCommand("  ", AccountIssuer.CHASE, AccountKind.CREDIT_CARD, null, "USD"),
                new CreateAccountCommand("a".repeat(121), AccountIssuer.CHASE, AccountKind.CREDIT_CARD, null, "USD"),
                new CreateAccountCommand("Synthetic card", null, AccountKind.CREDIT_CARD, null, "USD"),
                new CreateAccountCommand("Synthetic card", AccountIssuer.CHASE, null, null, "USD"),
                new CreateAccountCommand("Synthetic card", AccountIssuer.CHASE, AccountKind.CREDIT_CARD, "123", "USD"),
                new CreateAccountCommand("Synthetic card", AccountIssuer.CHASE, AccountKind.CREDIT_CARD, "12345", "USD"),
                new CreateAccountCommand("Synthetic card", AccountIssuer.CHASE, AccountKind.CREDIT_CARD, "12A4", "USD"),
                new CreateAccountCommand("Synthetic card", AccountIssuer.CHASE, AccountKind.CREDIT_CARD, "１２３４", "USD"),
                new CreateAccountCommand("Synthetic card", AccountIssuer.CHASE, AccountKind.CREDIT_CARD, null, null),
                new CreateAccountCommand("Synthetic card", AccountIssuer.CHASE, AccountKind.CREDIT_CARD, null, "EUR"),
                new CreateAccountCommand("Synthetic card", AccountIssuer.CHASE, AccountKind.CREDIT_CARD, null, "usd"));
    }

    @Test
    void normalizesDisplayFieldsAndLocksBeforePersistence() {
        AccountView view = service.create(actor, householdId,
                new CreateAccountCommand("  Synthetic card  ", AccountIssuer.AMEX,
                        AccountKind.CREDIT_CARD, " 0123 ", "USD"));

        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        InOrder order = inOrder(householdAccess, accounts);
        order.verify(householdAccess).lockAndRequireMember(actor, householdId);
        order.verify(accounts).save(saved.capture());
        assertEquals("Synthetic card", view.name());
        assertEquals("0123", view.lastFour());
        assertEquals(householdId, saved.getValue().getHouseholdId());
        assertNotNull(view.id());
        assertNotNull(view.createdAt());
        assertEquals(view.createdAt(), view.updatedAt());
        assertTrue(view.active());
    }

    @Test
    void blankOptionalLastFourBecomesNull() {
        AccountView view = service.create(actor, householdId,
                new CreateAccountCommand("Synthetic account", AccountIssuer.OTHER,
                        AccountKind.BANK_ACCOUNT, "   ", "USD"));
        assertEquals(null, view.lastFour());
        verify(accounts).save(any(Account.class));
    }

    @Test
    void deniedMembershipNeverReachesPersistence() {
        when(householdAccess.lockAndRequireMember(actor, householdId))
                .thenThrow(DomainException.forbidden());

        assertThrows(DomainException.class, () -> service.create(actor, householdId,
                new CreateAccountCommand("Synthetic account", AccountIssuer.CHASE,
                        AccountKind.BANK_ACCOUNT, null, "USD")));
        verifyNoInteractions(accounts);
    }

    @Test
    void invalidUpdateCannotMutateOrEvenLoadAnAccount() {
        assertThrows(DomainException.class, () -> service.update(actor, householdId,
                UUID.randomUUID(), new UpdateAccountCommand("Synthetic account",
                        AccountIssuer.CHASE, AccountKind.BANK_ACCOUNT, "invalid", "USD", false)));
        verify(householdAccess).lockAndRequireMember(actor, householdId);
        verifyNoInteractions(accounts);
    }

    @Test
    void missingActiveCannotBecomeAnAccidentalArchive() {
        assertThrows(DomainException.class, () -> service.update(actor, householdId,
                UUID.randomUUID(), new UpdateAccountCommand("Synthetic account",
                        AccountIssuer.CHASE, AccountKind.BANK_ACCOUNT, null, "USD", null)));
        verify(householdAccess).lockAndRequireMember(actor, householdId);
        verifyNoInteractions(accounts);
    }

    @Test
    void updateUsesOnlyHouseholdScopedLookup() {
        UUID foreignAccountId = UUID.randomUUID();
        when(accounts.findByIdAndHouseholdId(foreignAccountId, householdId))
                .thenReturn(Optional.empty());

        assertThrows(DomainException.class, () -> service.update(actor, householdId,
                foreignAccountId, new UpdateAccountCommand("Synthetic account",
                        AccountIssuer.CHASE, AccountKind.BANK_ACCOUNT, null, "USD", false)));
        InOrder order = inOrder(householdAccess, accounts);
        order.verify(householdAccess).lockAndRequireMember(actor, householdId);
        order.verify(accounts).findByIdAndHouseholdId(foreignAccountId, householdId);
        order.verifyNoMoreInteractions();
    }

    @Test
    void archivingPreservesHouseholdAndCreationTime() {
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        Account account = Account.create(householdId, "Synthetic card", AccountIssuer.CHASE,
                AccountKind.CREDIT_CARD, "1234", "USD", createdAt);
        when(accounts.findByIdAndHouseholdId(account.getId(), householdId))
                .thenReturn(Optional.of(account));

        AccountView view = service.update(actor, householdId, account.getId(),
                new UpdateAccountCommand("Renamed card", AccountIssuer.CHASE,
                        AccountKind.CREDIT_CARD, null, "USD", false));

        assertFalse(view.active());
        assertEquals(householdId, view.householdId());
        assertEquals(createdAt, view.createdAt());
        assertTrue(view.updatedAt().isAfter(createdAt));
        assertEquals("Renamed card", account.getName());
    }
}
