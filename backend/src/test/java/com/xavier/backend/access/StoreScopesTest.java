package com.xavier.backend.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.shared.persistence.StoreScopeViolations;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** D164 resolution order, D165 "a scope cannot change inside a connection", D166 executor propagation. */
class StoreScopesTest {

    static final UUID STORE = UUID.fromString("0190a000-0000-7000-8000-0000000000aa");
    static final UUID ACCOUNT = UUID.fromString("0190a000-0000-7000-8000-00000000a001");

    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final StoreScopeViolations violations = new StoreScopeViolations(registry, Clock.systemUTC());
    final StoreScopes scopes = new StoreScopes(() -> StoreScope.all("fallback"), violations);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void withNeitherAnActorNorAnExplicitScopeTheFallbackApplies() {
        assertThat(scopes.current().origin()).isEqualTo("fallback");
    }

    @Test
    void theAuthenticatedActorOutranksTheFallback() {
        authenticate(Actor.account(ACCOUNT, Set.of(Permission.ORDER_VIEW.authority(STORE))));

        assertThat(scopes.current().origin()).isEqualTo("account:" + ACCOUNT);
        assertThat(scopes.current().storeIdsLiteral()).isEqualTo("{" + STORE + "}");
    }

    @Test
    void theInnermostExplicitScopeOutranksTheActor() {
        authenticate(Actor.account(ACCOUNT, Set.of(Permission.ORDER_VIEW.authority(STORE))));

        String inner = scopes.call(SystemScope.ALERTS,
                () -> scopes.call(SystemScope.HOUSEKEEPING, () -> scopes.current().origin()));

        assertThat(inner).isEqualTo("system:HOUSEKEEPING");
        assertThat(scopes.current().origin()).as("restored after the call").isEqualTo("account:" + ACCOUNT);
    }

    @Test
    void aListenerRunsInItsEventsStore() {
        StoreScope inside = scopes.callForStore(STORE, "listener:X.on", scopes::current);

        assertThat(inside.allStoresSetting()).isEqualTo("off");
        assertThat(inside.storeIdsLiteral()).isEqualTo("{" + STORE + "}");
        assertThat(inside.origin()).isEqualTo("listener:X.on");
    }

    @Test
    void aListenerOfNoStoreFailsBeforeItRuns() {
        assertThatThrownBy(() -> scopes.callForStore(null, "listener:X.on", () -> "ran"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("listener:X.on");
    }

    @Test
    void aScopeCannotChangeInsideATransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(() -> scopes.run(SystemScope.ALERTS, () -> { }))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("transaction");
        assertThatThrownBy(() -> scopes.callForStore(STORE, "listener:X.on", () -> "ran"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aPropagatedTaskRunsInTheScopeCapturedAtSubmission() throws Exception {
        AtomicReference<StoreScope> seen = new AtomicReference<>();
        Runnable task = scopes.callForStore(STORE, "listener:X.on",
                () -> scopes.propagate(() -> seen.set(scopes.current())));

        CompletableFuture.runAsync(task, runnable -> new Thread(runnable).start()).get();

        assertThat(seen.get().storeIdsLiteral()).isEqualTo("{" + STORE + "}");
        assertThat(seen.get().origin()).isEqualTo("task:listener:X.on");
    }

    @Test
    void aTaskSubmittedFromATaskKeepsOneTaskPrefix() throws Exception {
        AtomicReference<String> origin = new AtomicReference<>();
        Runnable outer = scopes.call(SystemScope.PRINT_WORKER, () -> scopes.propagate(() ->
                scopes.propagate(() -> origin.set(scopes.current().origin())).run()));

        outer.run();

        assertThat(origin.get()).isEqualTo("task:system:PRINT_WORKER");
    }

    @Test
    void aPropagatedTaskThatHitsAPolicyIsCountedAndRethrown() {
        Runnable task = scopes.callForStore(STORE, "listener:X.on", () -> scopes.propagate(() -> {
            throw new DataAccessResourceFailureException("refused", new SQLException("no scope", "RS001"));
        }));

        assertThatThrownBy(task::run).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(registry.get("store_scope_violations").tag("kind", "scope_missing").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void resolvingTheActorThatNeedsTheDatabaseDoesNotRecurse() {
        Actor actor = Actor.account(ACCOUNT, Set.of(Permission.ORDER_VIEW.authority(STORE)));
        AtomicReference<StoreScope> nested = new AtomicReference<>();
        // Spring Security defers loading the context; loading it reads the session from JDBC, whose checkout asks
        // for the scope again on this thread
        SecurityContextHolder.setDeferredContext(() -> {
            nested.set(scopes.current());
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor, null, java.util.List.of()));
            return context;
        });

        StoreScope outer = scopes.current();

        assertThat(nested.get().origin()).as("the session read while loading the actor: the fallback")
                .isEqualTo("fallback");
        assertThat(outer.origin()).isEqualTo("account:" + ACCOUNT);
    }

    private static void authenticate(Actor actor) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(actor, null, java.util.List.of()));
    }
}
