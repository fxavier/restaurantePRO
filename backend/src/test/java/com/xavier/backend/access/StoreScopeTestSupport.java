package com.xavier.backend.access;

import com.xavier.backend.shared.persistence.StoreScopeViolations;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Supplier;
import org.awaitility.Awaitility;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The fixture fallback of D171. {@link StoreScopeFixtureExtension} marks the JUnit thread around each test (not for a
 * {@link StrictStoreScope} class, not during the context refresh, not while MockMvc dispatches a request:
 * {@link FixtureFreeRequests}); on a marked thread with neither an actor nor an explicit scope,
 * {@link #FIXTURE_FALLBACK} answers all stores (origin {@value #ORIGIN}), so fixture SQL keeps working. Every other
 * thread (listeners, schedulers, executors) gets production's unset scope.
 */
public final class StoreScopeTestSupport {

    public static final String ORIGIN = "test-fixture";

    /** The test context's {@link StoreScopeFallback}. */
    public static final StoreScopeFallback FIXTURE_FALLBACK = () -> isFixtureThread()
            ? StoreScope.all(ORIGIN) : StoreScope.UNSET;

    private static final ThreadLocal<Deque<Boolean>> MARKS = ThreadLocal.withInitial(ArrayDeque::new);

    private StoreScopeTestSupport() {
    }

    /** A {@link StoreScopes} with the fixture fallback, for unit tests that build a class needing one. */
    public static StoreScopes scopes() {
        return new StoreScopes(FIXTURE_FALLBACK, new StoreScopeViolations(new SimpleMeterRegistry(),
                Clock.systemUTC()));
    }

    /** Whether this thread runs fixture code of a non-strict test. */
    public static boolean isFixtureThread() {
        Boolean innermost = MARKS.get().peek();
        return innermost != null && innermost;
    }

    /**
     * Runs {@code fixture} with no actor in the security context, so a fixture seeding another store while a test's
     * actor is set gets the fallback (all stores) instead of the actor's scope. The context is restored after.
     */
    public static <T> T asFixture(Supplier<T> fixture) {
        SecurityContext saved = SecurityContextHolder.getContext();
        SecurityContextHolder.clearContext();
        try {
            return fixture.get();
        } finally {
            SecurityContextHolder.setContext(saved);
        }
    }

    /**
     * Runs {@code fixture} as fixture code of a non-strict test, also in a {@link StrictStoreScope} class: the thread
     * is marked and the actor cleared for the call only, so its SQL (and MockMvc requests, which unmark the thread
     * while they dispatch) see what a non-strict fixture sees. A test-only scope (origin {@value #ORIGIN}), so strict
     * tests never borrow a production {@link SystemScope} for their setup.
     */
    public static <T> T inStrictFixture(Supplier<T> fixture) {
        enter(true);
        try {
            return asFixture(fixture);
        } finally {
            leave();
        }
    }

    /** {@link #asFixture(Supplier)} for a fixture with no result. */
    public static void asFixture(Runnable fixture) {
        asFixture(() -> {
            fixture.run();
            return null;
        });
    }

    /**
     * Makes every Awaitility poll thread carry what its test thread has, the fixture mark and the security context,
     * as {@code pollInSameThread()} would: a condition is test code polled for the test, not a thread of the
     * application. Awaitility builds each await's poll thread from the awaiting thread, which is where both are read.
     * A global Awaitility default, installed once by {@link StoreScopeFixtureExtension}.
     */
    static void propagateToAwaitilityPollThreads() {
        Awaitility.pollThread(poll -> {
            boolean fixture = isFixtureThread();
            SecurityContext context = SecurityContextHolder.getContext();
            Thread thread = new Thread(() -> {
                enter(fixture);
                SecurityContextHolder.setContext(context);
                try {
                    poll.run();
                } finally {
                    SecurityContextHolder.clearContext();
                    leave();
                }
            }, "awaitility-thread");
            thread.setDaemon(true);
            return thread;
        });
    }

    static void enter(boolean fixture) {
        MARKS.get().push(fixture);
    }

    static void leave() {
        Deque<Boolean> marks = MARKS.get();
        marks.poll();
        if (marks.isEmpty()) {
            MARKS.remove();
        }
    }
}
