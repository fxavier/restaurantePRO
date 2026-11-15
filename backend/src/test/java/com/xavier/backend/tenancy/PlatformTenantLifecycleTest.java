package com.xavier.backend.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.organization.model.event.CompanyProvisioned;
import com.xavier.backend.shared.tenancy.MembershipKind;
import com.xavier.backend.shared.tenancy.StrictTenantScope;
import com.xavier.backend.shared.tenancy.TenantScopes;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import com.xavier.backend.tenancy.model.dto.Membership;
import com.xavier.backend.tenancy.model.dto.MembershipGrant;
import com.xavier.backend.tenancy.model.dto.TenantStatus;
import com.xavier.backend.tenancy.model.event.TenantClosed;
import com.xavier.backend.tenancy.model.event.TenantProvisioned;
import com.xavier.backend.tenancy.model.event.TenantReactivated;
import com.xavier.backend.tenancy.model.event.TenantSuspended;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * D181 as amended, the platform half of the tenant lifecycle over HTTP: onboarding is one idempotent command (the
 * tenant PROVISIONING, its Keycloak organization once, {@code TenantProvisioned} → the organization module's company
 * profile and first store → {@code CompanyProvisioned}; a replay changes nothing), and suspension takes effect on the
 * next resolution (S72's filter answers 403 {@code TENANT_SUSPENDED} from it) and on jobs (the tenant leaves
 * {@code active_tenant_ids()}). Every platform action is audited in {@code tenancy.platform_audit_event}. The identity
 * half (administrator, invite, ACCOUNT membership, activation) is S72's listener, proven by S72 and S76.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, PlatformTestSecurity.class, RecordingIdentityProvider.class,
        PlatformTenantLifecycleTest.Capture.class})
@StrictTenantScope
class PlatformTenantLifecycleTest {

    static final String TENANTS = "/api/v1/platform/tenants";
    static final String ISSUER = "https://auth.test/realms/restaurantpro";

    @Autowired
    MockMvc mvc;
    @Autowired
    TenancyApi tenancy;
    @Autowired
    TenantScopes tenants;

    @Test
    void onboardingIsOneIdempotentCommandThroughTheEventChain() throws Exception {
        String code = "acme-" + UUID.randomUUID().toString().substring(0, 8);

        UUID tenant = onboard(code, "Acme Lda").andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(code)).andExpect(jsonPath("$.status").value("PROVISIONING"))
                .andReturn().getResponse().getContentAsString().transform(PlatformTenantLifecycleTest::id);

        CompanyProvisioned company = Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> Capture.SEEN
                .stream().filter(CompanyProvisioned.class::isInstance).map(CompanyProvisioned.class::cast)
                .filter(event -> event.tenantId().equals(tenant)).findFirst().orElse(null), event -> event != null);
        assertThat(company.adminEmail()).isEqualTo("admin@" + code + ".example");
        assertThat(Capture.SEEN).filteredOn(TenantProvisioned.class::isInstance)
                .map(TenantProvisioned.class::cast).filteredOn(event -> event.tenantId().equals(tenant))
                .singleElement().satisfies(event -> {
                    assertThat(event.companyName()).isEqualTo("Acme Lda");
                    assertThat(event.firstStoreName()).isEqualTo("Loja Central");
                });

        onboard(code, "Acme Lda").andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(tenant.toString()));
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> completedProvisionings(tenant) == 2);

        assertThat(RecordingIdentityProvider.ORGANIZATIONS_CREATED).as("the organization is created once")
                .containsOnlyOnce(code);
        Map<String, Object> row = OwnerDatabase.shared().jdbc().sql("SELECT status, keycloak_org_id, legal_name "
                + "FROM tenancy.tenant WHERE id = ?").param(tenant).query().singleRow();
        assertThat(row).containsEntry("status", "PROVISIONING").containsEntry("keycloak_org_id", "org-" + code)
                .containsEntry("legal_name", "Acme Lda");
        assertThat(OwnerDatabase.shared().jdbc().sql("SELECT count(*) FROM organization.store WHERE tenant_id = ?")
                .param(tenant).query(Long.class).single()).as("one first store").isEqualTo(1);
        assertThat(Capture.SEEN).filteredOn(CompanyProvisioned.class::isInstance)
                .map(CompanyProvisioned.class::cast).filteredOn(event -> event.tenantId().equals(tenant)).hasSize(1);
        assertThat(audit(tenant)).containsExactly("TENANT_CREATED", "ORGANIZATION_LINKED", "ONBOARDING_PUBLISHED",
                "ONBOARDING_PUBLISHED");
        assertThat(OwnerDatabase.shared().jdbc().sql("SELECT DISTINCT operator FROM tenancy.platform_audit_event "
                + "WHERE tenant_id = ?").param(tenant).query(String.class).list())
                .containsExactly(PlatformTestSecurity.OPERATOR);

        onboard(code, "Another Name Lda").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TENANT_CODE_TAKEN"));
        close(tenant);
    }

    @Test
    void suspensionTakesEffectOnTheNextResolutionAndOnJobsAndIsReversible() throws Exception {
        UUID tenant = activeTenant();
        String subject = UUID.randomUUID().toString();
        TenantTestSupport.inTenant(tenants, tenant, () -> tenancy.grant(new MembershipGrant(ISSUER, subject,
                MembershipKind.ACCOUNT, UUID.randomUUID(), Instant.now().minusSeconds(1), null)));
        try {
            assertThat(resolve(subject)).isEqualTo(TenantStatus.ACTIVE);

            change(tenant, "suspend").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUSPENDED"));

            assertThat(resolve(subject)).as("the next request").isEqualTo(TenantStatus.SUSPENDED);
            assertThat(tenancy.activeTenantIds()).as("jobs skip it").doesNotContain(tenant);
            change(tenant, "suspend").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUSPENDED"));
            assertThat(events(TenantSuspended.class, tenant)).as("a repeated suspension is a no-op").hasSize(1);
            change(tenant, "close").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));

            assertThat(resolve(subject)).isEqualTo(TenantStatus.CLOSED);
            assertThat(events(TenantClosed.class, tenant)).hasSize(1);
            change(tenant, "reactivate").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ILLEGAL_TENANT_STATE"));
            assertThat(audit(tenant)).endsWith("TENANT_SUSPENDED", "TENANT_CLOSED");
        } finally {
            close(tenant);
        }
    }

    @Test
    void aReactivatedTenantServesAndRunsJobsAgainAndAnActiveOneCannotBeClosed() throws Exception {
        UUID tenant = activeTenant();
        try {
            change(tenant, "close").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ILLEGAL_TENANT_STATE"));
            change(tenant, "suspend").andExpect(status().isOk());

            change(tenant, "reactivate").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));

            assertThat(tenancy.activeTenantIds()).contains(tenant);
            assertThat(events(TenantReactivated.class, tenant)).hasSize(1);
        } finally {
            close(tenant);
        }
    }

    @Test
    void anUnknownTenantIsNotFoundAndAChangeNeedsAReason() throws Exception {
        change(UUID.randomUUID(), "suspend").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TENANT_NOT_FOUND"));
        mvc.perform(post(TENANTS + "/{id}/suspend", UUID.randomUUID()).with(PlatformTestSecurity.operator())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    /** A tenant onboarded over the API and activated as identity's listener does at the end of the chain. */
    private UUID activeTenant() throws Exception {
        String code = "life-" + UUID.randomUUID().toString().substring(0, 8);
        UUID tenant = id(onboard(code, "Life Lda").andExpect(status().isAccepted()).andReturn().getResponse()
                .getContentAsString());
        TenantTestSupport.inTenant(tenants, tenant, () -> tenancy.activate(tenant));
        assertThat(tenancy.activeTenantIds()).contains(tenant);
        return tenant;
    }

    private ResultActions onboard(String code, String legalName) throws Exception {
        return mvc.perform(post(TENANTS).with(PlatformTestSecurity.operator()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code":"%s","legalName":"%s","taxId":"400%s","country":"MZ","currency":"MZN",
                         "firstStoreName":"Loja Central","adminEmail":"admin@%s.example"}"""
                        .formatted(code, legalName, Math.abs(code.hashCode() % 1_000_000), code)));
    }

    private ResultActions change(UUID tenant, String action) throws Exception {
        return mvc.perform(post(TENANTS + "/{id}/" + action, tenant).with(PlatformTestSecurity.operator())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"test: " + action + "\"}"));
    }

    private TenantStatus resolve(String subject) {
        return tenancy.resolve(ISSUER, subject, null, Set.of(MembershipKind.ACCOUNT)).map(Membership::tenantStatus)
                .orElseThrow();
    }

    /** No ACTIVE test tenant is left behind for the other suites' jobs. */
    private static void close(UUID tenant) {
        TenantTestSupport.ensureTenant(tenant, OwnerDatabase.shared().jdbc().sql("SELECT code FROM tenancy.tenant "
                + "WHERE id = ?").param(tenant).query(String.class).single(), "CLOSED");
    }

    private static List<String> audit(UUID tenant) {
        return OwnerDatabase.shared().jdbc().sql("SELECT action FROM tenancy.platform_audit_event WHERE tenant_id = ? "
                + "ORDER BY occurred_at, id").param(tenant).query(String.class).list();
    }

    private static long completedProvisionings(UUID tenant) {
        return OwnerDatabase.shared().jdbc().sql("SELECT count(*) FROM event_publication WHERE event_type = ? "
                        + "AND serialized_event LIKE ? AND completion_date IS NOT NULL")
                .param(TenantProvisioned.class.getName()).param("%" + tenant + "%").query(Long.class).single();
    }

    private static <T> List<T> events(Class<T> type, UUID tenant) {
        return Capture.SEEN.stream().filter(type::isInstance).map(type::cast)
                .filter(event -> event.toString().contains(tenant.toString())).toList();
    }

    private static UUID id(String body) {
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    /** Records the lifecycle events, in their publisher's transaction. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Capture {

        static final List<Object> SEEN = new CopyOnWriteArrayList<>();

        @Bean
        Recorder lifecycleRecorder() {
            return new Recorder();
        }

        static class Recorder {

            @EventListener({TenantProvisioned.class, CompanyProvisioned.class, TenantSuspended.class,
                    TenantReactivated.class, TenantClosed.class})
            void on(Object event) {
                SEEN.add(event);
            }
        }
    }
}
