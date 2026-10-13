package com.expensetracker.foundation;

import com.expensetracker.households.domain.Role;
import com.expensetracker.shared.error.DomainException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HouseholdConcurrencyIT extends FoundationTestSupport {
    @ParameterizedTest(name = "two owners concurrently {0}: one owner must remain")
    @ValueSource(strings = {"leave", "demote"})
    void concurrentOwnerChangesCannotRemoveTheLastOwner(String operation) throws Exception {
        var shared = household();
        var secondOwner = member(shared.id(), Role.OWNER);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);

        try {
            var first = executor.submit(() -> attempt(ready, start, () -> {
                if (operation.equals("leave")) {
                    households.leave(shared.owner(), shared.id());
                } else {
                    households.changeRole(shared.owner(), shared.id(), shared.owner().userId(), Role.MEMBER);
                }
            }));
            var second = executor.submit(() -> attempt(ready, start, () -> {
                if (operation.equals("leave")) {
                    households.leave(secondOwner, shared.id());
                } else {
                    households.changeRole(secondOwner, shared.id(), secondOwner.userId(), Role.MEMBER);
                }
            }));

            assertTrue(ready.await(10, TimeUnit.SECONDS), "both threads must reach the starting barrier");
            start.countDown();
            var results = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));

            assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
            assertEquals(1, memberships.countByHouseholdIdAndRole(shared.id(), Role.OWNER));
            assertEquals(operation.equals("leave") ? 1 : 2, memberships.count());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private boolean attempt(CountDownLatch ready, CountDownLatch start, Runnable action) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("concurrent test start timed out");
        }
        try {
            // These are calls through the Spring proxy on separate threads/transactions.
            action.run();
            return true;
        } catch (DomainException error) {
            assertEquals(DomainException.Kind.CONFLICT, error.kind());
            assertEquals("last_owner", error.code());
            return false;
        }
    }
}
