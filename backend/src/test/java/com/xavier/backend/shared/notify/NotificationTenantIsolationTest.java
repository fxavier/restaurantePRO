package com.xavier.backend.shared.notify;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.shared.tenancy.StrictTenantScope;
import com.xavier.backend.shared.tenancy.TenantScopes;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import java.time.Duration;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.EmitterCapture;

/**
 * D185/D186 (security rule 7): a change notice goes through {@code pg_notify} in the writing transaction, keyed
 * {@code (tenantId, storeId)}. A stream of tenant B subscribed to the same store id as a stream of tenant A never
 * receives A's notice; A's stream receives it after the commit, and nothing after a rollback. Strict: no
 * bootstrap-tenant fallback.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@StrictTenantScope
class NotificationTenantIsolationTest {

    @Autowired
    ChangeNotifier notifier;
    @Autowired
    TenantScopes tenants;
    @Autowired
    PlatformTransactionManager transactions;

    @Test
    void aNoticeReachesOnlyTheStreamsOfItsTenantEvenForTheSameStoreId() {
        UUID store = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        UUID rolledBack = UUID.randomUUID();
        UUID afterwards = UUID.randomUUID();
        EmitterCapture inA = EmitterCapture.of(TenantTestSupport.inTenant(tenants, TenantTestSupport.A,
                () -> notifier.subscribe(store)));
        EmitterCapture inB = EmitterCapture.of(TenantTestSupport.inTenant(tenants, TenantTestSupport.B,
                () -> notifier.subscribe(store)));

        notifyIn(TenantTestSupport.A, store, order, false);
        notifyIn(TenantTestSupport.A, store, rolledBack, true);
        notifyIn(TenantTestSupport.B, store, afterwards, false);

        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> inB.text().contains(afterwards.toString()));
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> inA.text().contains(order.toString()));
        assertThat(inB.text()).as("tenant B's stream").doesNotContain(order.toString())
                .doesNotContain(rolledBack.toString());
        assertThat(inA.text()).as("tenant A's stream").doesNotContain(rolledBack.toString())
                .doesNotContain(afterwards.toString());
    }

    private void notifyIn(UUID tenant, UUID store, UUID id, boolean rollback) {
        TenantTestSupport.inTenant(tenants, tenant, () -> new TransactionTemplate(transactions)
                .executeWithoutResult(status -> {
                    notifier.notifyAfterCommit(store, ChangeTopic.ORDER, id);
                    if (rollback) {
                        status.setRollbackOnly();
                    }
                }));
    }
}
