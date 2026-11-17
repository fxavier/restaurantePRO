package com.xavier.backend.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.config.security.SecurityHttpTest;
import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import com.xavier.backend.tenancy.model.dto.PlatformTenantView;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * D181/D183, user story 22 and the G5 High, over the real security chain (S72: route family PLATFORM): only an
 * ops-realm token holding {@code platform-operator} reaches {@code /api/v1/platform/**} — a tenant-realm token, an
 * ops token without the role and a cashier's POS session are refused and change nothing — and the operator's request
 * runs with no tenant (its platform write passes the TN004 guard). The platform API cannot read anything: its only
 * routes are the lifecycle commands, which answer tenancy data only.
 */
@SecurityHttpTest
class PlatformAuthorizationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Test
    void aTenantRealmTokenIsRefusedEvenWithAPlatformOperatorRoleAndChangesNothing() throws Exception {
        UUID tenant = provisioningTenant();

        close(tenant, PlatformTokens.tenantUser()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLATFORM_FORBIDDEN"));

        assertThat(statusOf(tenant)).isEqualTo("PROVISIONING");
    }

    @Test
    void anOpsTokenWithoutTheOperatorRoleIsRefusedAndNoTokenIsUnauthenticated() throws Exception {
        UUID tenant = provisioningTenant();

        close(tenant, PlatformTokens.opsWithoutRole()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLATFORM_FORBIDDEN"));
        mvc.perform(post("/api/v1/platform/tenants/{id}/close", tenant).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"x\"}")).andExpect(status().isUnauthorized());

        assertThat(statusOf(tenant)).isEqualTo("PROVISIONING");
    }

    @Test
    void aCashiersPosSessionIsRefusedAndChangesNothing() throws Exception {
        IdentityFixtures fixtures = new IdentityFixtures(mvc);
        UUID store = fixtures.store();
        IdentityFixtures.Terminal terminal = fixtures.enrolledTerminal(store);
        IdentityFixtures.Employee cashier = fixtures.employee("4821", store, IdentityFixtures.WAITER);
        RequestPostProcessor session = fixtures.posLogin(terminal, cashier, "4821");
        UUID tenant = provisioningTenant();

        // the session carries the terminal's devices-realm token, which no platform route accepts
        int refused = mvc.perform(post("/api/v1/platform/tenants/{id}/close", tenant).with(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"a cashier\"}"))
                .andReturn().getResponse().getStatus();

        assertThat(refused).isIn(401, 403);
        assertThat(statusOf(tenant)).isEqualTo("PROVISIONING");
    }

    @Test
    void aPlatformOperatorIsAllowedAndRunsWithNoTenant() throws Exception {
        UUID tenant = provisioningTenant();

        close(tenant, PlatformTokens.operator()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        assertThat(statusOf(tenant)).as("TN004 did not fire: the request was unbound").isEqualTo("CLOSED");
        assertThat(OwnerDatabase.shared().jdbc().sql("SELECT operator FROM tenancy.platform_audit_event "
                        + "WHERE tenant_id = ? AND action = 'TENANT_CLOSED'").param(tenant).query(String.class).list())
                .containsExactly(PlatformTokens.OPERATOR);
    }

    @Test
    void thePlatformApiHasNoReadRouteAndAnswersTenancyDataOnly() {
        Map<RequestMappingInfo, HandlerMethod> platform = mappings.getHandlerMethods().entrySet().stream()
                .filter(entry -> entry.getKey().getPatternValues().stream()
                        .anyMatch(pattern -> pattern.startsWith("/api/v1/platform/tenants")))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertThat(platform).hasSize(4);
        assertThat(platform.keySet()).allSatisfy(info -> assertThat(info.getMethodsCondition().getMethods())
                .containsExactly(RequestMethod.POST));
        assertThat(platform.values()).allSatisfy(handler -> {
            assertThat(handler.getBeanType().getPackageName()).startsWith("com.xavier.backend.tenancy");
            assertThat(handler.getMethod().getReturnType()).isEqualTo(PlatformTenantView.class);
        });
    }

    private ResultActions close(UUID tenant, RequestPostProcessor token) throws Exception {
        return mvc.perform(post("/api/v1/platform/tenants/{id}/close", tenant).with(token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"platform authorization test\"}"));
    }

    /** A PROVISIONING tenant (never iterated by jobs): closing it is a platform write with no Keycloak call. */
    private static UUID provisioningTenant() {
        UUID tenant = UUID.randomUUID();
        TenantTestSupport.ensureTenant(tenant, "authz-" + tenant, "PROVISIONING");
        return tenant;
    }

    private static String statusOf(UUID tenant) {
        return OwnerDatabase.shared().jdbc().sql("SELECT status FROM tenancy.tenant WHERE id = ?").param(tenant)
                .query(String.class).single();
    }
}
