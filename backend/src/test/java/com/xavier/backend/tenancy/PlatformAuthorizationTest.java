package com.xavier.backend.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.shared.tenancy.StrictTenantScope;
import com.xavier.backend.tenancy.model.dto.PlatformTenantView;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * D181/D183 and user story 22: only an ops-realm token holding {@code platform-operator} reaches
 * {@code /api/v1/platform/**} (the rule is S72's {@code SecurityConfig}; here {@link PlatformTestSecurity} states it,
 * and S76 re-runs this contract over the real chain), a refused request changes nothing (no tenant, no Keycloak
 * call), and the platform API cannot read anything: its only routes are the tenancy lifecycle commands, which answer
 * tenancy data only. (That an ops token opens no business route is S72's route-family rule, D176; S76 proves it
 * over the real chain: {@code jwt()} here bypasses the chain's authentication.)
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, PlatformTestSecurity.class, RecordingIdentityProvider.class})
@StrictTenantScope
class PlatformAuthorizationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Test
    void aTenantRealmTokenIsRefusedEvenWithAPlatformOperatorRoleAndChangesNothing() throws Exception {
        String code = "forbidden-" + UUID.randomUUID().toString().substring(0, 8);

        onboard(code, PlatformTestSecurity.tenantUser()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLATFORM_FORBIDDEN"));

        assertThat(OwnerDatabase.shared().jdbc().sql("SELECT count(*) FROM tenancy.tenant WHERE code = ?")
                .param(code).query(Long.class).single()).isZero();
        assertThat(RecordingIdentityProvider.ORGANIZATIONS_CREATED).doesNotContain(code);
    }

    @Test
    void anOpsTokenWithoutTheOperatorRoleIsRefusedAndNoTokenIsUnauthenticated() throws Exception {
        String code = "norole-" + UUID.randomUUID().toString().substring(0, 8);

        onboard(code, PlatformTestSecurity.opsWithoutRole()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLATFORM_FORBIDDEN"));
        mvc.perform(post("/api/v1/platform/tenants/{id}/suspend", UUID.randomUUID())
                        .with(PlatformTestSecurity.opsWithoutRole()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/platform/tenants").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());

        assertThat(OwnerDatabase.shared().jdbc().sql("SELECT count(*) FROM tenancy.tenant WHERE code = ?")
                .param(code).query(Long.class).single()).isZero();
    }

    @Test
    void thePlatformApiHasNoReadRouteAndAnswersTenancyDataOnly() {
        Map<RequestMappingInfo, HandlerMethod> platform = mappings.getHandlerMethods().entrySet().stream()
                .filter(entry -> entry.getKey().getPatternValues().stream()
                        .anyMatch(pattern -> pattern.startsWith("/api/v1/platform")))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertThat(platform).hasSize(4);
        assertThat(platform.keySet()).allSatisfy(info -> assertThat(info.getMethodsCondition().getMethods())
                .containsExactly(RequestMethod.POST));
        assertThat(platform.values()).allSatisfy(handler -> {
            assertThat(handler.getBeanType().getPackageName()).startsWith("com.xavier.backend.tenancy");
            assertThat(handler.getMethod().getReturnType()).isEqualTo(PlatformTenantView.class);
        });
    }

    private org.springframework.test.web.servlet.ResultActions onboard(String code, RequestPostProcessor token)
            throws Exception {
        return mvc.perform(post("/api/v1/platform/tenants").with(token).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code":"%s","legalName":"Nope Lda","taxId":"400999","country":"MZ","currency":"MZN",
                         "firstStoreName":"Loja","adminEmail":"admin@%s.example"}""".formatted(code, code)));
    }
}
