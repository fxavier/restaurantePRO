package com.xavier.backend.organization;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.organization.model.dto.CompanyProfileView;
import com.xavier.backend.organization.model.dto.StoreSummary;
import com.xavier.backend.organization.model.event.CompanyProvisioned;
import com.xavier.backend.shared.tenancy.StrictTenantScope;
import com.xavier.backend.shared.tenancy.TenantScopes;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import com.xavier.backend.tenancy.model.event.TenantProvisioned;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D181 as amended, the organization half of onboarding: {@code TenantProvisioned} of a PROVISIONING tenant → in that
 * tenant's binding, its company profile and first store, then {@code CompanyProvisioned(tenantId, storeId,
 * adminEmail)} for identity (S72). A redelivery changes nothing and publishes nothing more; the tenant stays
 * PROVISIONING (identity activates it); the bootstrap tenant's profile is untouched.
 */
@OrganizationModuleTest
@StrictTenantScope
@Import(TenantProvisioningChainTest.Capture.class)
class TenantProvisioningChainTest {

    @Autowired
    ApplicationEventPublisher publisher;
    @Autowired
    PlatformTransactionManager transactions;
    @Autowired
    TenantScopes tenants;
    @Autowired
    OrganizationApi organization;

    @Test
    void aProvisionedTenantGetsItsCompanyAndFirstStoreThenCompanyProvisionedOnce() {
        UUID tenant = UUID.randomUUID();
        TenantTestSupport.ensureTenant(tenant, "chain-" + tenant, "PROVISIONING");
        TenantProvisioned provisioned = new TenantProvisioned(tenant, "Chain Lda", "400123456", "MZ", "MZN",
                "Loja Baixa", "admin@chain.example");

        publishIn(tenant, provisioned);

        CompanyProvisioned next = Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> Capture.SEEN
                .stream().filter(event -> event.tenantId().equals(tenant)).findFirst().orElse(null), e -> e != null);
        assertThat(next.adminEmail()).isEqualTo("admin@chain.example");
        CompanyProfileView company = TenantTestSupport.inTenant(tenants, tenant, organization::companyProfile);
        assertThat(company.name()).isEqualTo("Chain Lda");
        assertThat(company.taxId()).isEqualTo("400123456");
        assertThat(company.currency().getCurrencyCode()).isEqualTo("MZN");
        StoreSummary store = TenantTestSupport.inTenant(tenants, tenant, () -> organization.findStore(next.storeId()))
                .orElseThrow();
        assertThat(store.name()).isEqualTo("Loja Baixa");
        assertThat(store.code()).isEqualTo("001");

        publishIn(tenant, provisioned); // a redelivery
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> completed(tenant) == 2);

        assertThat(Capture.SEEN).filteredOn(event -> event.tenantId().equals(tenant)).hasSize(1);
        assertThat(OwnerDatabase.shared().jdbc().sql("SELECT count(*) FROM organization.store WHERE tenant_id = ?")
                .param(tenant).query(Long.class).single()).isEqualTo(1);
        assertThat(OwnerDatabase.shared().jdbc().sql("SELECT status FROM tenancy.tenant WHERE id = ?").param(tenant)
                .query(String.class).single()).as("identity activates it (S72)").isEqualTo("PROVISIONING");
        assertThat(TenantTestSupport.inTenant(tenants, TenantTestSupport.BOOTSTRAP,
                () -> organization.companyProfile().name())).isNotEqualTo("Chain Lda");
    }

    private void publishIn(UUID tenant, Object event) {
        TenantTestSupport.inTenant(tenants, tenant, () -> new TransactionTemplate(transactions)
                .executeWithoutResult(status -> publisher.publishEvent(event)));
    }

    private static long completed(UUID tenant) {
        return OwnerDatabase.shared().jdbc().sql("SELECT count(*) FROM event_publication WHERE event_type = ? "
                        + "AND serialized_event LIKE ? AND completion_date IS NOT NULL")
                .param(TenantProvisioned.class.getName()).param("%" + tenant + "%").query(Long.class).single();
    }

    /** Records every {@code CompanyProvisioned}, in its publisher's transaction. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Capture {

        static final List<CompanyProvisioned> SEEN = new CopyOnWriteArrayList<>();

        @Bean
        Recorder companyProvisionedRecorder() {
            return new Recorder();
        }

        static class Recorder {

            @EventListener
            void on(CompanyProvisioned event) {
                SEEN.add(event);
            }
        }
    }
}
