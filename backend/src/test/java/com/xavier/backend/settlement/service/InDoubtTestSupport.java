package com.xavier.backend.settlement.service;

import com.xavier.backend.shared.tenancy.TenantScopes;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationContext;

/** For suites outside settlement (the alert outlet): an overdue in-doubt attempt seeded and refreshed in one tenant. */
public final class InDoubtTestSupport {

    private InDoubtTestSupport() {
    }

    /** An UNKNOWN attempt 40 min old of a fresh store in {@code tenant}, then the in-doubt gauge refreshed bound to it. */
    public static UUID overdueAttemptIn(ApplicationContext context, UUID tenant, Instant now) {
        UUID store = UUID.randomUUID();
        InDoubtGaugeTenantTest.attemptInDoubt(tenant, store, now);
        TenantTestSupport.inTenant(context.getBean(TenantScopes.class), tenant,
                context.getBean(InDoubtGauge.class)::refresh);
        return store;
    }
}
