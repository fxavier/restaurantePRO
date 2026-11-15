package com.xavier.backend.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.config.security.SecurityHttpTest;
import com.xavier.backend.config.security.TestJwtFactory;
import com.xavier.backend.organization.model.event.CompanyProvisioned;
import com.xavier.backend.shared.tenancy.TenantScopes;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import com.xavier.backend.tenancy.IdentityProviderAdmin;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D181 as amended, the identity half of onboarding (the identity-provider port faked): {@code CompanyProvisioned} →
 * in the event's tenant, its administrator role and the local administrator account (the administrator's e-mail as
 * the username), the Keycloak user invited, the {@code ACCOUNT} membership granted to that user, and the tenant
 * activated; the invited user then logs in as the administrator. A redelivery changes nothing.
 */
@SecurityHttpTest
@Import(TenantProvisioningChainTest.FakeIdentityProvider.class)
class TenantProvisioningChainTest {

    @Autowired
    ApplicationEventPublisher publisher;
    @Autowired
    PlatformTransactionManager transactions;
    @Autowired
    TenantScopes tenants;
    @Autowired
    MockMvc mvc;

    @Test
    void aProvisionedCompanyGetsItsAdministratorMembershipAndIsActivatedOnce() throws Exception {
        UUID tenant = UUID.randomUUID();
        TenantTestSupport.ensureTenant(tenant, "chain-" + tenant, "PROVISIONING");
        String email = "admin-" + tenant + "@chain.example";
        CompanyProvisioned provisioned = new CompanyProvisioned(tenant, UUID.randomUUID(), email);

        publishIn(tenant, provisioned);
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> status(tenant).equals("ACTIVE"));

        assertThat(FakeIdentityProvider.INVITED).containsExactly(email);
        UUID admin = OwnerDatabase.shared().jdbc().sql("SELECT id FROM identity.user_account WHERE tenant_id = ? "
                + "AND username = ?").param(tenant).param(email).query(UUID.class).single();
        assertThat(OwnerDatabase.shared().jdbc().sql("SELECT count(*) FROM identity.user_account_role ur "
                        + "JOIN identity.role r ON r.id = ur.role_id WHERE ur.user_account_id = ? AND r.administrator")
                .param(admin).query(Long.class).single()).isEqualTo(1);
        String subject = FakeIdentityProvider.SUBJECTS.get(email);
        assertThat(OwnerDatabase.shared().jdbc().sql("SELECT principal_id FROM tenancy.tenant_membership "
                        + "WHERE tenant_id = ? AND issuer = ? AND subject = ? AND kind = 'ACCOUNT' AND status = 'ACTIVE'")
                .param(tenant).param(TestJwtFactory.TENANT_ISSUER).param(subject).query(UUID.class).single())
                .isEqualTo(admin);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/session")
                        .with(TestJwtFactory.account(subject).bearer()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.userId")
                        .value(admin.toString()));

        publishIn(tenant, provisioned); // a redelivery
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> completed(tenant) == 2);

        assertThat(OwnerDatabase.shared().jdbc().sql("SELECT count(*) FROM identity.user_account WHERE tenant_id = ?")
                .param(tenant).query(Long.class).single()).isEqualTo(1);
        assertThat(OwnerDatabase.shared().jdbc().sql("SELECT count(*) FROM tenancy.tenant_membership "
                + "WHERE tenant_id = ?").param(tenant).query(Long.class).single()).isEqualTo(1);
        assertThat(status(tenant)).isEqualTo("ACTIVE");
    }

    private void publishIn(UUID tenant, Object event) {
        TenantTestSupport.inTenant(tenants, tenant, () -> new TransactionTemplate(transactions)
                .executeWithoutResult(status -> publisher.publishEvent(event)));
    }

    private static String status(UUID tenant) {
        return OwnerDatabase.shared().jdbc().sql("SELECT status FROM tenancy.tenant WHERE id = ?").param(tenant)
                .query(String.class).single();
    }

    private static long completed(UUID tenant) {
        return OwnerDatabase.shared().jdbc().sql("SELECT count(*) FROM event_publication WHERE event_type = ? "
                        + "AND serialized_event LIKE ? AND completion_date IS NOT NULL")
                .param(CompanyProvisioned.class.getName()).param("%" + tenant + "%").query(Long.class).single();
    }

    /** The identity provider's admin port, faked: an invitation answers a stable user id per address. */
    @TestConfiguration(proxyBeanMethods = false)
    static class FakeIdentityProvider {

        static final List<String> INVITED = new CopyOnWriteArrayList<>();
        static final Map<String, String> SUBJECTS = new ConcurrentHashMap<>();

        @Bean
        @Primary
        IdentityProviderAdmin fakeIdentityProviderAdmin() {
            return new IdentityProviderAdmin() {
                @Override
                public String createOrganization(String alias, String name) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void addOrganizationMember(String organizationId, String userId) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public String inviteUser(String email, String organizationId) {
                    if (!INVITED.contains(email)) {
                        INVITED.add(email);
                    }
                    return SUBJECTS.computeIfAbsent(email, ignored -> "kc-" + UUID.randomUUID());
                }

                @Override
                public List<IdentitySession> userSessions(String userId) {
                    return List.of();
                }

                @Override
                public void logoutUser(String userId) {
                }

                @Override
                public String createClient(String clientId) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void registerClientKey(String clientId, String publicJwk) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void disableClient(String clientId) {
                    throw new UnsupportedOperationException();
                }
            };
        }
    }
}
