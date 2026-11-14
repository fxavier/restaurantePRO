package com.xavier.backend.access;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;

/**
 * Marks the JUnit thread as a fixture thread around each test: its {@code @BeforeEach}, body and {@code @AfterEach}.
 * Not during {@code @BeforeAll}/{@code @AfterAll} nor the Spring context refresh (the test instance, and with it the
 * context, is prepared before the before-each callbacks), so startup code keeps production semantics. A request
 * dispatched by MockMvc is unmarked by {@link FixtureFreeRequests}. Registered for every test by auto-detection
 * ({@code junit-platform.properties}); a {@link StrictStoreScope} class is never marked. Static fixtures of
 * {@code @BeforeAll} use {@code OwnerDatabase} or an explicit scope.
 */
public class StoreScopeFixtureExtension implements BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        StoreScopeTestSupport.enter(!strict(context));
    }

    @Override
    public void afterEach(ExtensionContext context) {
        StoreScopeTestSupport.leave();
    }

    private static boolean strict(ExtensionContext context) {
        for (Class<?> type = context.getRequiredTestClass(); type != null; type = type.getEnclosingClass()) {
            if (AnnotationSupport.isAnnotated(type, StrictStoreScope.class)) {
                return true;
            }
        }
        return false;
    }
}
