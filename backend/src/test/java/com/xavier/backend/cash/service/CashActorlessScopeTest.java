package com.xavier.backend.cash.service;

import static com.xavier.backend.cash.CashFixtures.pos;
import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.cash.CashFixtures;
import com.xavier.backend.cash.CashFixtures.Pos;
import com.xavier.backend.cash.CashModuleTest;
import com.xavier.backend.cash.CashTestClock;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A74 for cash under its policies (V8011, D166): the {@code CASH_DISCREPANCY} refresh, cash's one actor-less entry
 * point ({@code ALERTS}), runs on a scheduler-like thread (no actor, no fixture fallback) with every store in scope,
 * so a closing short in store A and one in store B both reach the gauge, and nothing raises {@code RS001}.
 */
@CashModuleTest
class CashActorlessScopeTest {

    @Autowired
    CashFixtures fixtures;

    @Autowired
    CashDiscrepancyAlertSource source;

    @Autowired
    MeterRegistry meters;

    @Autowired
    CashTestClock clock;

    @Test
    void theDiscrepancyRefreshSeesTheClosingsOfEveryStore() throws Exception {
        UUID a = storeWithAClosingFiveShort();
        UUID b = storeWithAClosingFiveShort();

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread job = Thread.ofPlatform().name("s67-job").start(() -> {
            try {
                source.refresh();
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        job.join(Duration.ofSeconds(60));
        assertThat(job.isAlive()).as("the refresh finished").isFalse();

        assertThat(failure.get()).as("the refresh failed").isNull();
        assertThat(differences(a)).isEqualTo(1);
        assertThat(differences(b)).isEqualTo(1);
    }

    /**
     * D185: the scheduler's one run covers every ACTIVE tenant, each in its own binding, and the one bean keeps what
     * every tenant's run found (a later tenant's run does not wipe an earlier one's gauge row).
     */
    @Test
    void theDiscrepancyRefreshRunsOncePerActiveTenantAndKeepsAllOfThem() throws Exception {
        UUID inA = UUID.randomUUID();
        UUID inB = UUID.randomUUID();
        try {
            TenantTestSupport.ensureTenant(TenantTestSupport.A, "tenant-a", "ACTIVE");
            TenantTestSupport.ensureTenant(TenantTestSupport.B, "tenant-b", "ACTIVE");
            CashDiscrepancyTenantTest.submittedClosingFiveShort(TenantTestSupport.A, inA, clock.instant());
            CashDiscrepancyTenantTest.submittedClosingFiveShort(TenantTestSupport.B, inB, clock.instant());

            runOnAJobThread();

            assertThat(differences(inA)).isEqualTo(1);
            assertThat(differences(inB)).isEqualTo(1);
            assertThat(source.alerts()).extracting(alert -> alert.storeId()).contains(inA, inB);
        } finally {
            TenantTestSupport.ensureTenants(); // back to PROVISIONING: other suites' jobs iterate the bootstrap only
        }
    }

    private void runOnAJobThread() throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread job = Thread.ofPlatform().name("s74b-job").start(() -> {
            try {
                source.refresh();
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        job.join(Duration.ofSeconds(60));
        assertThat(job.isAlive()).as("the refresh finished").isFalse();
        assertThat(failure.get()).as("the refresh failed").isNull();
    }

    private UUID storeWithAClosingFiveShort() throws Exception {
        UUID store = fixtures.newStore();
        UUID register = fixtures.newRegister(store);
        Pos operator = pos(store);
        UUID session = fixtures.openSession(operator, register, "100.00");
        fixtures.closeSession(operator, session, "95.00");
        fixtures.submittedClosing(operator, register, fixtures.dayOf(session));
        return store;
    }

    private double differences(UUID store) {
        return meters.get("cash_closings_with_difference").tag("store", store.toString()).gauge().value();
    }
}
