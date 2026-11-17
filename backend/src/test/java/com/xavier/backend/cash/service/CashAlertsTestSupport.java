package com.xavier.backend.cash.service;

import com.xavier.backend.shared.tenancy.TenantScopes;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationContext;

/** For suites outside cash (the alert outlet): a CASH_DISCREPANCY seeded and refreshed in one tenant. */
public final class CashAlertsTestSupport {

    private CashAlertsTestSupport() {
    }

    /** A 5.00-short closing of a fresh store in {@code tenant}, then the discrepancy refresh run bound to it. */
    public static UUID discrepancyIn(ApplicationContext context, UUID tenant, Instant now) {
        UUID store = UUID.randomUUID();
        CashDiscrepancyTenantTest.submittedClosingFiveShort(tenant, store, now);
        TenantTestSupport.inTenant(context.getBean(TenantScopes.class), tenant,
                context.getBean(CashDiscrepancyAlertSource.class)::refresh);
        return store;
    }
}
