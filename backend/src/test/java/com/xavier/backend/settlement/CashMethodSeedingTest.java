package com.xavier.backend.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.organization.model.event.CompanyProvisioned;
import com.xavier.backend.shared.tenancy.StrictTenantScope;
import com.xavier.backend.shared.tenancy.TenantScopes;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import java.time.Duration;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A new tenant has no CASH payment method (the seeded one is the bootstrap tenant's): settlement seeds it when the
 * tenant's company is provisioned. Two tenants each get their own, and a redelivery adds none.
 */
@SettlementModuleTest
@StrictTenantScope
class CashMethodSeedingTest {

    @Autowired
    ApplicationEventPublisher publisher;
    @Autowired
    PlatformTransactionManager transactions;
    @Autowired
    TenantScopes tenants;

    final java.util.List<UUID> provisioned = new java.util.ArrayList<>();

    /**
     * The identity listener of this context also handles these events (activating the tenant, creating its roles):
     * leave neither an ACTIVE tenant (other suites' jobs would iterate it) nor an event another context republishes.
     */
    @AfterEach
    void leaveNothingBehind() {
        provisioned.forEach(tenant -> {
            TenantTestSupport.ensureTenant(tenant, "cash-" + tenant, "CLOSED");
            OwnerDatabase.shared().jdbc().sql("DELETE FROM event_publication WHERE serialized_event LIKE ?")
                    .param("%" + tenant + "%").update();
        });
    }

    @Test
    void eachProvisionedTenantGetsItsOwnCashMethodOnceEvenOnRedelivery() {
        UUID one = UUID.randomUUID();
        UUID two = UUID.randomUUID();
        for (UUID tenant : new UUID[]{one, two}) {
            provisioned.add(tenant);
            TenantTestSupport.ensureTenant(tenant, "cash-" + tenant, "PROVISIONING");
            publish(tenant);
        }
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> cashMethods(one) == 1 && cashMethods(two) == 1);

        publish(one); // a redelivery
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> completed(one) == 2);

        assertThat(cashMethods(one)).isEqualTo(1);
        assertThat(cashMethods(two)).isEqualTo(1);
        assertThat(OwnerDatabase.shared().jdbc().sql("SELECT count(DISTINCT id) FROM settlement.payment_method "
                + "WHERE code = 'CASH' AND tenant_id IN (?, ?)").param(one).param(two).query(Long.class).single())
                .isEqualTo(2);
    }

    private void publish(UUID tenant) {
        TenantTestSupport.inTenant(tenants, tenant, () -> new TransactionTemplate(transactions).executeWithoutResult(
                status -> publisher.publishEvent(new CompanyProvisioned(tenant, UUID.randomUUID(), "a@x.example"))));
    }

    private static long cashMethods(UUID tenant) {
        return OwnerDatabase.shared().jdbc().sql("SELECT count(*) FROM settlement.payment_method "
                + "WHERE code = 'CASH' AND tenant_id = ?").param(tenant).query(Long.class).single();
    }

    private static long completed(UUID tenant) {
        return OwnerDatabase.shared().jdbc().sql("SELECT count(*) FROM event_publication WHERE event_type = ? "
                        + "AND serialized_event LIKE ? AND completion_date IS NOT NULL")
                .param(CompanyProvisioned.class.getName()).param("%" + tenant + "%").query(Long.class).single();
    }
}
