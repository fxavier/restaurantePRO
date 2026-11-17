package com.xavier.backend.config.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * D177 amended: {@code GET /api/v1/auth/tenants} lists the caller's own active memberships (tenant id, code, legal
 * name, kind and the tenant's status), needs no {@code X-Tenant-Id} even with several, and never names a tenant the
 * caller does not belong to nor a revoked membership.
 */
@SecurityHttpTest
class TenantListTest {

    @Autowired
    MockMvc mvc;

    @Test
    void anAccountantSeesItsTwoTenantsAndNothingElse() throws Exception {
        UUID first = Members.activeTenant();
        UUID second = Members.activeTenant();
        UUID other = Members.activeTenant();
        String accountant = "kc-" + UUID.randomUUID();
        Members.member(first, accountant);
        Members.member(second, accountant);
        Members.member(other, "kc-" + UUID.randomUUID());

        mvc.perform(get("/api/v1/auth/tenants").with(TestJwtFactory.account(accountant).bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].tenantId", Matchers.containsInAnyOrder(first.toString(), second.toString())))
                .andExpect(jsonPath("$[*].kind", Matchers.everyItem(Matchers.is("ACCOUNT"))));
    }

    @Test
    void eachListedTenantNamesItsCodeLegalNameAndStatus() throws Exception {
        UUID tenant = Members.activeTenant();
        String subject = "kc-" + UUID.randomUUID();
        Members.member(tenant, subject);

        mvc.perform(get("/api/v1/auth/tenants").with(TestJwtFactory.account(subject).bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tenantId").value(tenant.toString()))
                .andExpect(jsonPath("$[0].code").value("t-" + tenant))
                .andExpect(jsonPath("$[0].legalName").value("t-" + tenant))
                .andExpect(jsonPath("$[0].kind").value("ACCOUNT"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].keycloakOrgId").doesNotExist());
    }

    @Test
    void aRevokedMembershipIsNotListed() throws Exception {
        UUID tenant = Members.activeTenant();
        String subject = "kc-" + UUID.randomUUID();
        UUID membership = Members.accountMembership(tenant, subject, Members.account(tenant));
        Members.revoke(membership);

        mvc.perform(get("/api/v1/auth/tenants").with(TestJwtFactory.account(subject).bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aStrangerSeesAnEmptyList() throws Exception {
        mvc.perform(get("/api/v1/auth/tenants").with(TestJwtFactory.account("kc-" + UUID.randomUUID()).bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }
}
