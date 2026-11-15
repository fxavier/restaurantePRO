package com.xavier.backend.cash.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.AppRoleSession;
import com.xavier.backend.cash.CashModuleTest;
import com.xavier.backend.cash.CashTestClock;
import com.xavier.backend.shared.ops.OperationalAlert;
import com.xavier.backend.shared.tenancy.StrictTenantScope;
import com.xavier.backend.shared.tenancy.TenantScopes;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * D185 (A90): the scheduler runs the alert source once per tenant, one after the other, on the one bean. A tenant's
 * run must not erase what an earlier tenant's run found: A's CASH_DISCREPANCY alert and gauge survive B's run.
 */
@CashModuleTest
@StrictTenantScope
class CashDiscrepancyTenantTest {

    static final UUID A = TenantTestSupport.A;
    static final UUID B = TenantTestSupport.B;

    @Autowired
    CashDiscrepancyAlertSource source;
    @Autowired
    TenantScopes tenants;
    @Autowired
    MeterRegistry meters;
    @Autowired
    CashTestClock clock;

    @AfterEach
    void releaseClock() {
        clock.release();
    }

    @Test
    void aTenantsDiscrepancyStaysAlertedAfterAnotherTenantsRun() {
        TenantTestSupport.ensureTenants();
        UUID storeA = UUID.randomUUID();
        UUID storeB = UUID.randomUUID();
        submittedClosingFiveShort(A, storeA, clock.instant());
        submittedClosingFiveShort(B, storeB, clock.instant());

        TenantTestSupport.inTenant(tenants, A, source::refresh);
        TenantTestSupport.inTenant(tenants, B, source::refresh);

        assertThat(source.alerts()).extracting(OperationalAlert::storeId).contains(storeA, storeB);
        assertThat(meters.get("cash_closings_with_difference").tag("store", storeA.toString()).gauge().value())
                .isEqualTo(1);
        assertThat(meters.get("cash_closings_with_difference").tag("store", storeB.toString()).gauge().value())
                .isEqualTo(1);
    }

    /** A SUBMITTED closing (at {@code now}) of a fresh store whose cash line is 5.00 short, in {@code tenant}. */
    static void submittedClosingFiveShort(UUID tenant, UUID store, Instant now) {
        try (AppRoleSession app = AppRoleSession.open().tenant(tenant).allStores()) {
            UUID register = UUID.randomUUID();
            UUID day = UUID.randomUUID();
            UUID closing = UUID.randomUUID();
            Timestamp submitted = Timestamp.from(now);
            app.jdbc().sql("INSERT INTO cash.cash_register (id, store_id, code, name, active, version, created_at, "
                    + "updated_at) VALUES (?, ?, 'R1', 'Till', true, 0, now(), now())").param(register).param(store)
                    .update();
            app.jdbc().sql("INSERT INTO cash.cash_day (id, store_id, business_date, status, version, opened_at) "
                    + "VALUES (?, ?, ?, 'OPEN', 0, now())").param(day).param(store).param(LocalDate.of(2026, 11, 5))
                    .update();
            app.jdbc().sql("""
                    INSERT INTO cash.cash_closing (id, store_id, register_id, cash_day_id, attempt_no, status,
                        version, currency, created_at, created_by_employee_id, updated_at, submitted_at,
                        submitted_by_employee_id)
                    VALUES (?, ?, ?, ?, 1, 'SUBMITTED', 0, 'MZN', ?, ?, ?, ?, ?)""")
                    .params(closing, store, register, day, submitted, UUID.randomUUID(), submitted, submitted,
                            UUID.randomUUID()).update();
            app.jdbc().sql("""
                    INSERT INTO cash.cash_closing_line (id, closing_id, method_code, expected_amount,
                        counted_amount, difference_amount, currency, justification)
                    VALUES (?, ?, 'CASH', 100.00, 95.00, -5.00, 'MZN', 'coins')""")
                    .params(UUID.randomUUID(), closing).update();
        }
    }
}
