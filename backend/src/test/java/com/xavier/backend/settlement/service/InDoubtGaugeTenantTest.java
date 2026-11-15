package com.xavier.backend.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.AppRoleSession;
import com.xavier.backend.settlement.SettlementModuleTest;
import com.xavier.backend.settlement.SettlementTestClock;
import com.xavier.backend.settlement.service.InDoubtGauge.OverdueInStore;
import com.xavier.backend.shared.tenancy.StrictTenantScope;
import com.xavier.backend.shared.tenancy.TenantScopes;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * D185 (A90): the scheduler runs the gauge once per tenant, one after the other, on the one bean. A tenant's run must
 * not erase what an earlier tenant's run reported: A's store keeps its count, and its overdue attempt stays alerted,
 * after B's run.
 */
@SettlementModuleTest
@StrictTenantScope
class InDoubtGaugeTenantTest {

    static final UUID A = TenantTestSupport.A;
    static final UUID B = TenantTestSupport.B;

    @Autowired
    InDoubtGauge gauge;
    @Autowired
    TenantScopes tenants;
    @Autowired
    MeterRegistry meters;
    @Autowired
    SettlementTestClock clock;

    @AfterEach
    void restore() {
        clock.reset();
    }

    @Test
    void aTenantsInDoubtAttemptsStayReportedAfterAnotherTenantsRun() {
        TenantTestSupport.ensureTenants();
        UUID storeA = UUID.randomUUID();
        UUID storeB = UUID.randomUUID();
        attemptInDoubt(A, storeA);
        attemptInDoubt(B, storeB);

        TenantTestSupport.inTenant(tenants, A, gauge::refresh);
        TenantTestSupport.inTenant(tenants, B, gauge::refresh);

        assertThat(value("payment_attempts_in_doubt", storeA)).isEqualTo(1);
        assertThat(value("payment_attempts_in_doubt", storeB)).isEqualTo(1);
        assertThat(value("payment_attempts_in_doubt_overdue", storeA)).isEqualTo(1);
        assertThat(value("payment_attempts_in_doubt_overdue", storeB)).isEqualTo(1);
        assertThat(gauge.overdueAtLastRefresh()).extracting(OverdueInStore::storeId).contains(storeA, storeB);
    }

    private void attemptInDoubt(UUID tenant, UUID store) {
        try (AppRoleSession app = AppRoleSession.open().tenant(tenant).allStores()) {
            Timestamp created = Timestamp.from(clock.instant().minus(Duration.ofMinutes(40)));
            app.jdbc().sql("""
                    INSERT INTO settlement.payment_attempt (id, order_id, store_id, terminal_id, session_id,
                        method_code, method_kind, provider_code, provider_reference, amount, currency, status,
                        created_by_employee_id, created_at, updated_at, version)
                    VALUES (?, ?, ?, ?, ?, 'FAKE_M', 'INTEGRATED', 'FAKE', ?, 25.00, 'MZN', 'UNKNOWN', ?, ?, ?, 0)""")
                    .params(UUID.randomUUID(), UUID.randomUUID(), store, UUID.randomUUID(), UUID.randomUUID(),
                            UUID.randomUUID().toString(), UUID.randomUUID(), created, created).update();
        }
    }

    private double value(String name, UUID store) {
        return meters.get(name).tag("store", store.toString()).gauge().value();
    }
}
