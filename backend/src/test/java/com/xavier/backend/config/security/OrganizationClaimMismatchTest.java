package com.xavier.backend.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.OwnerDatabase;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * D177 step 8: the token's {@code organization} claim is compared with the resolved tenant's Keycloak organization. A
 * mismatch is logged and counted ({@code tenant_claim_mismatch_total}) and never grants, refuses or changes anything:
 * the tenant still comes from the membership alone.
 */
@SecurityHttpTest
class OrganizationClaimMismatchTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    MeterRegistry meters;

    @Test
    void aClaimOfAnotherOrganizationIsCountedAndChangesNothing() throws Exception {
        UUID tenant = tenantInOrganization("org-" + UUID.randomUUID());
        String subject = "kc-" + UUID.randomUUID();
        UUID account = Members.member(tenant, subject);
        double before = mismatches();

        mvc.perform(get("/api/v1/auth/session").with(TestJwtFactory.account(subject)
                        .claim("organization", Map.of("other-alias", Map.of("id", "org-elsewhere"))).bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(account.toString()));

        assertThat(mismatches()).isEqualTo(before + 1);
    }

    @Test
    void theTenantsOwnOrganizationIsNoMismatch() throws Exception {
        String organization = "org-" + UUID.randomUUID();
        UUID tenant = tenantInOrganization(organization);
        String subject = "kc-" + UUID.randomUUID();
        Members.member(tenant, subject);
        double before = mismatches();

        mvc.perform(get("/api/v1/auth/session").with(TestJwtFactory.account(subject)
                        .claim("organization", Map.of("t-" + tenant, Map.of("id", organization))).bearer()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/session").with(TestJwtFactory.account(subject).bearer()))
                .andExpect(status().isOk());

        assertThat(mismatches()).isEqualTo(before);
    }

    private static UUID tenantInOrganization(String organization) {
        UUID tenant = Members.activeTenant();
        OwnerDatabase.shared().jdbc().sql("UPDATE tenancy.tenant SET keycloak_org_id = ? WHERE id = ?")
                .param(organization).param(tenant).update();
        return tenant;
    }

    private double mismatches() {
        var counter = meters.find("tenant_claim_mismatch_total").counter();
        return counter == null ? 0 : counter.count();
    }
}
