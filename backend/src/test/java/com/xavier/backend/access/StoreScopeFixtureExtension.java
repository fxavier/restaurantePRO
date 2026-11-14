package com.xavier.backend.access;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;

/**
 * Marks the JUnit thread as a fixture thread while a test class runs, from its {@code @BeforeAll} to its
 * {@code @AfterAll}, and again around each test (a test may run on another thread). Registered for every test by
 * auto-detection ({@code junit-platform.properties}). A {@link StrictStoreScope} class is marked strict instead.
 */
public class StoreScopeFixtureExtension implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback,
        AfterEachCallback {

    @Override
    public void beforeAll(ExtensionContext context) {
        StoreScopeTestSupport.enter(!strict(context));
    }

    @Override
    public void afterAll(ExtensionContext context) {
        StoreScopeTestSupport.leave();
    }

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
