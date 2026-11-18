package com.xavier.backend.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.shared.tenancy.TenantTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * A81, D177, security rules 1 and 5: the tenant of a request comes from an authorised membership of the token's
 * {@code (iss, sub)}; {@code X-Tenant-Id} only selects among them. A forged selection of an existing foreign tenant
 * gets exactly the body of a random one (no oracle); several memberships need a selection that lists nothing; a
 * suspended or closed tenant, a revoked membership and an inactive account are refused on the next request; the
 * {@code organization} claim changes nothing. The probe {@code GET /api/v1/auth/session} answers the resolved actor.
 */
@SecurityHttpTest
class TenantResolutionTest {

    private static final String SESSION = "/api/v1/auth/session";

    @Autowired
    MockMvc mvc;

    private static String subject() {
        return "kc-" + UUID.randomUUID();
    }

    private static MockHttpServletRequestBuilder session(String subject) {
        return get(SESSION).with(TestJwtFactory.account(subject).bearer());
    }

    @Test
    void aMemberActsInItsTenantAsItsLocalAccount() throws Exception {
        UUID tenant = Members.activeTenant();
        String subject = subject();
        UUID account = Members.member(tenant, subject);

        mvc.perform(session(subject)).andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("ACCOUNT"))
                .andExpect(jsonPath("$.userId").value(account.toString()));
    }

    @Test
    void aValidTokenWithoutMembershipIsForbidden() throws Exception {
        mvc.perform(session(subject())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_FORBIDDEN"));
    }

    @Test
    void aForgedForeignTenantGetsTheBodyOfARandomOne() throws Exception {
        UUID mine = Members.activeTenant();
        UUID foreign = Members.activeTenant();
        String subject = subject();
        Members.member(mine, subject);
        Members.member(foreign, subject()); // the foreign tenant exists and has members

        String forged = mvc.perform(session(subject).header("X-Tenant-Id", foreign.toString()))
                .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
        String random = mvc.perform(session(subject).header("X-Tenant-Id", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();

        assertThat(forged).contains("TENANT_FORBIDDEN").isEqualTo(random);
    }

    @Test
    void aMalformedSelectionIsABadRequest() throws Exception {
        mvc.perform(session(subject()).header("X-Tenant-Id", "not-a-uuid")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TENANT_SELECTION_INVALID"));
    }

    @Test
    void severalMembershipsNeedASelectionThatListsNothing() throws Exception {
        UUID first = Members.activeTenant();
        UUID second = Members.activeTenant();
        String subject = subject();
        Members.member(first, subject);
        UUID inSecond = Members.member(second, subject);

        String body = mvc.perform(session(subject)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TENANT_SELECTION_REQUIRED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(first.toString()).doesNotContain(second.toString());

        mvc.perform(session(subject).header("X-Tenant-Id", second.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(inSecond.toString()));
    }

    @Test
    void theSessionNamesTheTenantTheRequestWasResolvedIn() throws Exception {
        UUID first = Members.activeTenant();
        UUID second = Members.activeTenant();
        String subject = subject();
        Members.member(first, subject);
        Members.member(second, subject);

        mvc.perform(session(subject).header("X-Tenant-Id", second.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(second.toString()))
                .andExpect(jsonPath("$.tenantName").value("t-" + second));
        mvc.perform(session(subject).header("X-Tenant-Id", first.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(first.toString()))
                .andExpect(jsonPath("$.tenantName").value("t-" + first));
    }

    @Test
    void aSuspendedTenantIsRefused() throws Exception {
        UUID tenant = Members.activeTenant();
        String subject = subject();
        Members.member(tenant, subject);
        TenantTestSupport.ensureTenant(tenant, "t-" + tenant, "SUSPENDED");

        mvc.perform(session(subject)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_SUSPENDED"));
    }

    @Test
    void aClosedTenantIsRefused() throws Exception {
        UUID tenant = Members.activeTenant();
        String subject = subject();
        Members.member(tenant, subject);
        TenantTestSupport.ensureTenant(tenant, "t-" + tenant, "CLOSED");

        mvc.perform(session(subject)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_CLOSED"));
    }

    @Test
    void aRevokedMembershipIsRefusedOnTheNextRequest() throws Exception {
        UUID tenant = Members.activeTenant();
        String subject = subject();
        UUID account = Members.account(tenant);
        UUID membership = Members.accountMembership(tenant, subject, account);
        mvc.perform(session(subject)).andExpect(status().isOk());

        Members.revoke(membership);

        mvc.perform(session(subject)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_FORBIDDEN"));
    }

    @Test
    void anInactiveAccountIsRefused() throws Exception {
        UUID tenant = Members.activeTenant();
        String subject = subject();
        UUID account = Members.member(tenant, subject);
        Members.deactivate(account);

        mvc.perform(session(subject)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_FORBIDDEN"));
    }

    @Test
    void anOrganizationClaimOfAnotherTenantChangesNothing() throws Exception {
        UUID tenant = Members.activeTenant();
        String subject = subject();
        UUID account = Members.member(tenant, subject);

        mvc.perform(get(SESSION).with(TestJwtFactory.account(subject)
                        .claim("organization", java.util.Map.of("other-tenant", java.util.Map.of())).bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(account.toString()));
    }

    @Test
    void aTenantMembershipNeverResolvesAnOpsRealmToken() throws Exception {
        UUID tenant = Members.activeTenant();
        String subject = subject();
        Members.member(tenant, subject); // an ACCOUNT membership of the tenant realm's subject

        mvc.perform(get(SESSION).with(TestJwtFactory.support(subject, java.time.Instant.now()).bearer()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_FORBIDDEN"));
    }

    /** D177 amended: the ops realm resolves SUPPORT memberships only, whatever else names its subject. */
    @Test
    void anOpsRealmTokenNeverResolvesAnAccountMembership() throws Exception {
        UUID tenant = Members.activeTenant();
        String subject = subject();
        Members.membership(tenant, TestJwtFactory.OPS_ISSUER, subject, "ACCOUNT", Members.account(tenant),
                java.time.Instant.now().minusSeconds(60), null);

        mvc.perform(get(SESSION).with(TestJwtFactory.support(subject, java.time.Instant.now()).bearer()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_FORBIDDEN"));
    }

    @Test
    void thePlatformApiNeedsAPlatformOperatorOfTheOpsRealm() throws Exception {
        mvc.perform(get("/api/v1/platform/tenants").with(TestJwtFactory.account(subject()).bearer()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PLATFORM_FORBIDDEN"));
        mvc.perform(get("/api/v1/platform/tenants").with(TestJwtFactory.support(subject(), java.time.Instant.now())
                        .bearer()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PLATFORM_FORBIDDEN"));
    }
}
