package com.xavier.backend.access;

import java.time.Clock;
import java.time.Instant;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/**
 * The application clock of the running Spring test, for fixtures that build an authentication by hand
 * ({@link TestAuthentications}): a second factor is fresh on the clock {@code AccessPolicy} reads (D184), also in a
 * context whose clock a test moved. Registered for every Spring test in {@code META-INF/spring.factories}; without a
 * loaded context or a {@link Clock} bean it is the system clock.
 */
public final class TestClocks implements TestExecutionListener {

    /** Not thread-local: a test's request builders also run on its worker threads; test classes run one at a time. */
    private static volatile Clock current;

    /** Now, on the running test's application clock. */
    public static Instant now() {
        Clock clock = current;
        return clock == null ? Instant.now() : clock.instant();
    }

    @Override
    public void beforeTestMethod(TestContext testContext) {
        if (testContext.hasApplicationContext()) {
            current = testContext.getApplicationContext().getBeanProvider(Clock.class).getIfAvailable();
        }
    }

    @Override
    public void afterTestMethod(TestContext testContext) {
        current = null;
    }
}
