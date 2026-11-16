package com.xavier.backend.settlement.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.settlement.SettlementModuleTest;
import com.xavier.backend.settlement.reconciliation.repository.ReconciliationBatchRepository;
import com.xavier.backend.shared.tenancy.StrictTenantScope;
import com.xavier.backend.shared.tenancy.TenantScopes;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The statement-import advisory lock is per tenant and method code: tenant A holding it never delays tenant B. */
@SettlementModuleTest
@StrictTenantScope
class ImportLockTenantTest {

    @Autowired
    ReconciliationBatchRepository batches;
    @Autowired
    TenantScopes tenants;
    @Autowired
    PlatformTransactionManager transactions;

    @Test
    void anotherTenantsImportOfTheSameMethodCodeIsNotBlocked() throws Exception {
        TenantTestSupport.ensureTenants();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> inTransactionOf(TenantTestSupport.A, () -> {
            batches.lockImportsOf("CARD");
            held.countDown();
            await(release);
        }));
        assertThat(held.await(30, TimeUnit.SECONDS)).isTrue();
        try {
            CompletableFuture<Void> other = CompletableFuture.runAsync(() -> inTransactionOf(TenantTestSupport.B,
                    () -> batches.lockImportsOf("CARD")));

            other.get(10, TimeUnit.SECONDS); // a wait for A's lock times out here

            assertThat(other).isCompleted();
        } finally {
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
        }
    }

    private void inTransactionOf(UUID tenant, Runnable work) {
        TenantTestSupport.inTenant(tenants, tenant, () -> new TransactionTemplate(transactions)
                .executeWithoutResult(status -> work.run()));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
