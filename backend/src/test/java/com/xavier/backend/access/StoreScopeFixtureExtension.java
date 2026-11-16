package com.xavier.backend.access;

import com.xavier.backend.shared.tenancy.StrictTenantScope;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;

/**
 * Marks the JUnit thread as a fixture thread around each test: its {@code @BeforeEach}, body and {@code @AfterEach},
 * for the store scope (D171) and, separately, for the bootstrap tenant (D189: not for a {@code StrictTenantScope}
 * class; a MockMvc request keeps the tenant mark, as it runs in the request's tenant anyway).
 * Not during {@code @BeforeAll}/{@code @AfterAll} nor the Spring context refresh (the test instance, and with it the
 * context, is prepared before the before-each callbacks), so startup code keeps production semantics. A request
 * dispatched by MockMvc is unmarked by {@link FixtureFreeRequests}. Registered for every test by auto-detection
 * ({@code junit-platform.properties}); a {@link StrictStoreScope} class is never marked. Static fixtures of
 * {@code @BeforeAll} use {@code OwnerDatabase} or an explicit scope. An Awaitility condition polled by a test gets
 * the test thread's mark and actor ({@link StoreScopeTestSupport#propagateToAwaitilityPollThreads}).
 * An application a test body starts or closes itself (a simulated restart) runs its startup and shutdown unmarked
 * ({@link ApplicationLifecycleMarks}).
 */
public class StoreScopeFixtureExtension implements BeforeEachCallback, AfterEachCallback {

    static {
        StoreScopeTestSupport.propagateToAwaitilityPollThreads();
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        StoreScopeTestSupport.enter(!strict(context, StrictStoreScope.class));
        boolean strictTenant = strict(context, StrictTenantScope.class);
        TenantTestSupport.enter(!strictTenant);
        TenantTestSupport.strictClass(strictTenant);
        StoreScopeTestSupport.enterTestBody(true);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        TenantTestSupport.strictClass(false);
        StoreScopeTestSupport.leaveTestBody();
        TenantTestSupport.leave();
        StoreScopeTestSupport.leave();
    }

    private static boolean strict(ExtensionContext context, Class<? extends java.lang.annotation.Annotation> mark) {
        for (Class<?> type = context.getRequiredTestClass(); type != null; type = type.getEnclosingClass()) {
            if (AnnotationSupport.isAnnotated(type, mark)) {
                return true;
            }
        }
        return false;
    }
}
