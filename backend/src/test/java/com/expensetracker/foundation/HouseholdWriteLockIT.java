package com.expensetracker.foundation;

import com.expensetracker.accounts.application.CreateAccountCommand;
import com.expensetracker.accounts.domain.AccountIssuer;
import com.expensetracker.accounts.domain.AccountKind;
import com.expensetracker.households.domain.Role;
import com.expensetracker.shared.error.DomainException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HouseholdWriteLockIT extends FoundationTestSupport {
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void waitingWriteChecksMembershipAfterTheHouseholdLockIsReleased() throws Exception {
        var shared = household();
        var member = member(shared.id(), Role.MEMBER);
        var command = new CreateAccountCommand("Must not be created", AccountIssuer.CHASE,
                AccountKind.CREDIT_CARD, "1234", "USD");
        var waiterPid = new CompletableFuture<Integer>();
        var transactions = new TransactionTemplate(transactionManager);
        transactions.setTimeout(20);
        var executor = Executors.newSingleThreadExecutor();

        try {
            Future<DomainException> waitingWrite = transactions.execute(status -> {
                boundedDatabaseWaits();
                householdRepository.findLockedById(shared.id()).orElseThrow();
                int lockHolderPid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);

                Future<DomainException> mutation = executor.submit(() -> {
                    try {
                        transactions.execute(waitingStatus -> {
                            boundedDatabaseWaits();
                            waiterPid.complete(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                            // The application service joins this separate Spring transaction.
                            accounts.create(member, shared.id(), command);
                            return null;
                        });
                        return null;
                    } catch (DomainException denied) {
                        return denied;
                    }
                });

                int blockedPid = awaitBackendPid(waiterPid);
                assertNotEquals(lockHolderPid, blockedPid,
                        "lock holder and waiting write need distinct database connections");
                awaitPostgresLockWait(lockHolderPid, blockedPid, mutation);
                assertFalse(mutation.isDone(), "the write must still wait while the household is locked");

                // Test-only fixture mutation follows the same household lock held by transaction A.
                assertEquals(1, jdbc.update("DELETE FROM memberships WHERE household_id = ? AND user_id = ?",
                        shared.id(), member.userId()));
                return mutation;
                // Transaction A commits here, before we await transaction B's result.
            });

            assertNotNull(waitingWrite);
            DomainException denied = waitingWrite.get(15, TimeUnit.SECONDS);
            assertNotNull(denied, "the revoked member must be denied after obtaining the lock");
            assertEquals(DomainException.Kind.NOT_FOUND, denied.kind());
            assertEquals("not_found", denied.code());
            assertTrue(accountQueries.list(shared.owner(), shared.id()).isEmpty());
            assertFalse(memberships.findByHouseholdIdAndUserId(shared.id(), member.userId()).isPresent());
        } finally {
            // TransactionTemplate commits or rolls back A before this finally block, releasing its row lock.
            // B's database timeouts also bound cleanup if an assertion fails during observation.
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS), "waiting transaction must terminate");
        }
    }

    private void boundedDatabaseWaits() {
        jdbc.execute("SET LOCAL lock_timeout = '10s'");
        jdbc.execute("SET LOCAL statement_timeout = '20s'");
    }

    private int awaitBackendPid(CompletableFuture<Integer> pid) {
        try {
            return pid.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while awaiting the write connection", interrupted);
        } catch (ExecutionException | TimeoutException failed) {
            throw new AssertionError("the write did not report its connection within five seconds", failed);
        }
    }

    private void awaitPostgresLockWait(int holderPid, int waiterPid, Future<?> write) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            // PostgreSQL can retain a statistics snapshot inside A's transaction; refresh it on each poll.
            jdbc.execute("SELECT pg_stat_clear_snapshot()");
            Boolean waiting = jdbc.queryForObject("""
                    SELECT COALESCE(wait_event_type = 'Lock', FALSE)
                           AND ? = ANY(pg_blocking_pids(pid))
                    FROM pg_stat_activity
                    WHERE pid = ?
                    """, Boolean.class, holderPid, waiterPid);
            if (Boolean.TRUE.equals(waiting)) {
                return;
            }
            assertFalse(write.isDone(), "the write completed without waiting for the household lock");
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while observing the PostgreSQL lock", interrupted);
            }
        }
        throw new AssertionError("PostgreSQL did not observe the write blocked by the household lock");
    }
}
