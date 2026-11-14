package com.xavier.backend.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * D171: an Awaitility condition is test code evaluated on Awaitility's poll thread for the test, so it gets what the
 * test thread has, as {@code pollInSameThread()} would give it: the fixture mark and the security context (so an
 * actor's poll is scoped to the actor). Threads of the application keep production semantics.
 */
class AwaitilityFixtureScopeTest {

    static final StoreScopes SCOPES = StoreScopeTestSupport.scopes();

    @AfterEach
    void clearActor() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aConditionOfAFixtureThreadSeesEveryStoreOnThePollThread() {
        AtomicReference<String> seen = new AtomicReference<>();

        Awaitility.await().until(() -> {
            seen.set(Thread.currentThread().getName() + "|" + SCOPES.current().origin());
            return true;
        });

        assertThat(seen.get()).isNotEqualTo(Thread.currentThread().getName() + "|test-fixture")
                .endsWith("|test-fixture");
    }

    @Test
    void aConditionPolledForAnActorRunsAsTheActor() {
        UUID account = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                Actor.account(account, Set.of()), null, java.util.List.of()));
        AtomicReference<String> seen = new AtomicReference<>();

        Awaitility.await().until(() -> {
            seen.set(SCOPES.current().origin());
            return true;
        });

        assertThat(seen.get()).isEqualTo("account:" + account);
    }

    @Nested
    @StrictStoreScope
    class Strict {

        @Test
        void aConditionOfAStrictTestIsUnset() {
            AtomicReference<StoreScope> seen = new AtomicReference<>();

            Awaitility.await().until(() -> {
                seen.set(SCOPES.current());
                return true;
            });

            assertThat(seen.get()).isEqualTo(StoreScope.UNSET);
        }
    }
}
