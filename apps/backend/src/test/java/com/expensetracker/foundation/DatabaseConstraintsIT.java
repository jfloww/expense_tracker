package com.expensetracker.foundation;

import com.expensetracker.accounts.application.CreateAccountCommand;
import com.expensetracker.accounts.domain.AccountIssuer;
import com.expensetracker.accounts.domain.AccountKind;
import com.expensetracker.households.domain.Role;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Raw autocommit SQL proves constraints independently of application validation. */
class DatabaseConstraintsIT extends FoundationTestSupport {
    @Test
    void flywayAppliedVersionOneSuccessfullyOnPostgresql() {
        assertEquals("PostgreSQL", jdbc.execute((java.sql.Connection connection) ->
                connection.getMetaData().getDatabaseProductName()));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE NOT success", Integer.class));
    }

    @Test
    void databaseRejectsDuplicateMembershipAndUserEmail() {
        var shared = household();
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO memberships (id, household_id, user_id, role, created_at)
                VALUES (?, ?, ?, 'MEMBER', now())
                """, UUID.randomUUID(), shared.id(), shared.owner().userId()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO app_users (id, email, display_name, password_hash, enabled, created_at)
                SELECT ?, email, 'Duplicate user', password_hash, true, now()
                FROM app_users WHERE id = ?
                """, UUID.randomUUID(), shared.owner().userId()));

        assertEquals(1, memberships.count());
        assertEquals(1, users.count());
    }

    @Test
    void restrictiveForeignKeysPreventDeletingSharedHouseholdOrIdentity() {
        var shared = household();
        var card = accounts.create(shared.owner(), shared.id(), new CreateAccountCommand(
                "Retained shared card", AccountIssuer.CAPITAL_ONE, AccountKind.CREDIT_CARD, "1234", "USD"));

        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("DELETE FROM households WHERE id = ?", shared.id()));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("DELETE FROM app_users WHERE id = ?", shared.owner().userId()));

        assertEquals(1, householdRepository.count());
        assertEquals(1, memberships.count());
        assertEquals(1, users.count());
        assertEquals(card.id(), accountQueries.get(shared.owner(), shared.id(), card.id()).id());
    }

    @Test
    void accountForeignKeyIsRestrictiveEvenAfterMembershipsAreRemoved() {
        var shared = household();
        var card = accounts.create(shared.owner(), shared.id(), new CreateAccountCommand(
                "Restrictive account", AccountIssuer.CHASE, AccountKind.BANK_ACCOUNT, null, "USD"));
        jdbc.update("DELETE FROM memberships WHERE household_id = ?", shared.id());

        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("DELETE FROM households WHERE id = ?", shared.id()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM accounts WHERE id = ?", Integer.class, card.id()));
        assertEquals(1, householdRepository.count());
    }

    @Test
    void databaseRejectsInvalidRoleAndUnknownRelatedUser() {
        var shared = household();
        var member = member(shared.id(), Role.MEMBER);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE memberships SET role = 'ADMIN' WHERE household_id = ? AND user_id = ?",
                shared.id(), member.userId()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO memberships (id, household_id, user_id, role, created_at)
                VALUES (?, ?, ?, 'MEMBER', now())
                """, UUID.randomUUID(), shared.id(), UUID.randomUUID()));

        assertEquals(Role.MEMBER, householdQueries.get(member, shared.id()).role());
        assertEquals(2, memberships.count());
    }

    @Test
    void databaseRejectsUnsupportedCurrencyAndInvalidLastFour() {
        var shared = household();
        var card = accounts.create(shared.owner(), shared.id(), new CreateAccountCommand(
                "Valid USD card", AccountIssuer.AMEX, AccountKind.CREDIT_CARD, "1234", "USD"));

        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE accounts SET currency = 'EUR' WHERE id = ?", card.id()));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE accounts SET last_four = '12x4' WHERE id = ?", card.id()));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE accounts SET name = '   ' WHERE id = ?", card.id()));

        var unchanged = accountQueries.get(shared.owner(), shared.id(), card.id());
        assertEquals("USD", unchanged.currency());
        assertEquals("1234", unchanged.lastFour());
        assertEquals("Valid USD card", unchanged.name());
        assertTrue(unchanged.active());
    }
}
