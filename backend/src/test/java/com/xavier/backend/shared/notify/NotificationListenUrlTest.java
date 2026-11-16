package com.xavier.backend.shared.notify;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.AppRoleSession;
import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.shared.tenancy.TenantScopes;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import java.time.Duration;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.EmitterCapture;

/**
 * D185 behind a transaction-mode pooler (S75's PgBouncer drill): LISTEN through PgBouncer receives nothing, so the
 * change-notice listener connects to {@code restaurantpro.notify.listen-url} (direct to PostgreSQL), as the pool's
 * role, while the writes keep the pool. Here the URL differs only by its {@code ApplicationName}, which tells the
 * listener's session apart.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class NotificationListenUrlTest {

    static final String LISTENER = "s71-change-notice-listener";

    @DynamicPropertySource
    static void directListenUrl(DynamicPropertyRegistry properties) {
        String url = TestcontainersConfiguration.postgres().getJdbcUrl();
        properties.add("restaurantpro.notify.listen-url",
                () -> url + (url.contains("?") ? "&" : "?") + "ApplicationName=" + LISTENER);
    }

    @Autowired
    ChangeNotifier notifier;
    @Autowired
    TenantScopes tenants;
    @Autowired
    PlatformTransactionManager transactions;

    @Test
    void theListenerConnectsToTheListenUrlAndStillDeliversNotices() {
        try (AppRoleSession app = AppRoleSession.open()) {
            Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> app.jdbc().sql("SELECT count(*) FROM "
                    + "pg_stat_activity WHERE application_name = ? AND query ILIKE 'LISTEN%'").param(LISTENER)
                    .query(Long.class).single() > 0);
        }
        UUID store = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        EmitterCapture stream = EmitterCapture.of(TenantTestSupport.inTenant(tenants, TenantTestSupport.BOOTSTRAP,
                () -> notifier.subscribe(store)));

        TenantTestSupport.inTenant(tenants, TenantTestSupport.BOOTSTRAP, () -> new TransactionTemplate(transactions)
                .executeWithoutResult(status -> notifier.notifyAfterCommit(store, ChangeTopic.ORDER, order)));

        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> stream.text().contains(order.toString()));
    }
}
