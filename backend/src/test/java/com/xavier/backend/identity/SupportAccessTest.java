package com.xavier.backend.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.access.Permission;
import com.xavier.backend.config.security.Members;
import com.xavier.backend.config.security.SecurityHttpTest;
import com.xavier.backend.config.security.TestJwtFactory;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * D183 (amended: at most 24 h): a tenant administrator grants a support person (a user of the ops realm, named by its
 * {@code sub}) time-boxed access to a support account. Only that person, with an ops-realm token, a second factor and
 * {@code X-Tenant-Id}, then acts in that tenant as the support account, and only while the grant lasts; the ops
 * issuer is accepted for {@code SUPPORT} memberships only. Support's first action is audited in the tenant and shown
 * to its administrators as an in-app alert.
 */
@SecurityHttpTest
class SupportAccessTest {

    static final String GRANTS = "/api/v1/backoffice/support-grants";
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    UUID tenant;
    String admin;
    UUID supportAccount;
    String supporter;

    @BeforeEach
    void aTenantWithAnAdministratorAndASupportAccount() {
        tenant = Members.activeTenant();
        admin = "kc-" + UUID.randomUUID();
        Members.member(tenant, admin, Members.role(tenant, Permission.SUPPORT_ACCESS_MANAGE,
                Permission.SYSTEM_MONITOR));
        supportAccount = Members.account(tenant, true, Members.role(tenant, Permission.CATALOG_MANAGE,
                Permission.USER_MANAGE));
        supporter = "ops-" + UUID.randomUUID();
    }

    private ResultActions grant(int minutes) throws Exception {
        return mvc.perform(post(GRANTS).with(TestJwtFactory.account(admin).bearer())
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"accountId": "%s", "subject": "%s", "durationMinutes": %d, "reason": "ticket 9"}"""
                        .formatted(supportAccount, supporter, minutes)));
    }

    private UUID granted() throws Exception {
        return UUID.fromString(JSON.readTree(grant(60).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString()).get("id").asString());
    }

    private MockHttpServletRequestBuilder asSupport(String path, TestJwtFactory token) {
        return get(path).with(token.bearer()).header("X-Tenant-Id", tenant.toString());
    }

    private TestJwtFactory supportToken() {
        return TestJwtFactory.support(supporter, Instant.now());
    }

    @Test
    void theGrantedPersonActsInTheTenantAsTheSupportAccount() throws Exception {
        granted();

        mvc.perform(asSupport("/api/v1/auth/session", supportToken())).andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(supportAccount.toString()))
                .andExpect(jsonPath("$.tenantId").value(tenant.toString()))
                .andExpect(jsonPath("$.tenantName").value("t-" + tenant));
        mvc.perform(asSupport("/api/v1/backoffice/catalog/families", supportToken())).andExpect(status().isOk());
    }

    @Test
    void supportNeverHoldsWhatWidensAccess() throws Exception {
        granted();

        String session = mvc.perform(asSupport("/api/v1/auth/session", supportToken())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(session).contains("PERM_CATALOG_MANAGE").doesNotContain("PERM_USER_MANAGE");
    }

    @Test
    void aGrantLongerThan24HoursIsRefused() throws Exception {
        grant(24 * 60 + 1).andExpect(status().isBadRequest());
    }

    @Test
    void supportWithoutASecondFactorGetsTheChallengeOnEveryRequest() throws Exception {
        granted();

        mvc.perform(asSupport("/api/v1/auth/session", supportToken().authenticated("1", Instant.now())))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.containsString(
                        "insufficient_user_authentication")));
    }

    @Test
    void theSameSubjectWithATenantRealmTokenIsNotSupport() throws Exception {
        granted();

        mvc.perform(asSupport("/api/v1/auth/session", TestJwtFactory.account(supporter)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_FORBIDDEN"));
    }

    @Test
    void anotherSupportPersonIsRefused() throws Exception {
        granted();

        mvc.perform(asSupport("/api/v1/auth/session", TestJwtFactory.support("ops-" + UUID.randomUUID(),
                        Instant.now())))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_FORBIDDEN"));
    }

    @Test
    void aRevokedGrantIsRefusedOnTheNextRequest() throws Exception {
        UUID grant = granted();
        mvc.perform(asSupport("/api/v1/auth/session", supportToken())).andExpect(status().isOk());

        mvc.perform(post(GRANTS + "/" + grant + "/revoke").with(TestJwtFactory.account(admin).bearer())
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isOk());

        mvc.perform(asSupport("/api/v1/auth/session", supportToken())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_FORBIDDEN"));
    }

    @Test
    void anEndedGrantIsRefused() throws Exception {
        UUID grant = granted();
        OwnerDatabase.shared().tx(jdbc -> {
            jdbc.sql("UPDATE identity.support_grant SET valid_from = now() - interval '2 hours', "
                    + "valid_until = now() - interval '1 hour' WHERE id = ?").param(grant).update();
            jdbc.sql("UPDATE tenancy.tenant_membership SET valid_from = now() - interval '2 hours', "
                    + "valid_until = now() - interval '1 hour' WHERE principal_id = ?").param(grant).update();
        });

        mvc.perform(asSupport("/api/v1/auth/session", supportToken())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_FORBIDDEN"));
    }

    /** D183: every support action is audited in the tenant with actor kind SUPPORT, the session start included. */
    @Test
    void aSupportActionIsAuditedAsSupport() throws Exception {
        granted();
        String code = "S" + UUID.randomUUID().toString().substring(0, 8);

        mvc.perform(post("/api/v1/backoffice/catalog/families").with(supportToken().bearer())
                        .header("X-Tenant-Id", tenant.toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "%s", "name": "Support family"}""".formatted(code)))
                .andExpect(status().isCreated());

        assertThat(OwnerDatabase.shared().jdbc().sql("SELECT action || ' ' || actor_kind FROM audit.audit_event "
                        + "WHERE tenant_id = ? AND actor_id = ? AND action IN ('FAMILY_CREATED', "
                        + "'SUPPORT_SESSION_STARTED') ORDER BY action")
                .param(tenant).param(supportAccount).query(String.class).list())
                .containsExactly("FAMILY_CREATED SUPPORT", "SUPPORT_SESSION_STARTED SUPPORT");
    }

    @Test
    void theFirstSupportActionIsAuditedAndAlertedToTheAdministrators() throws Exception {
        UUID grant = granted();

        mvc.perform(asSupport("/api/v1/auth/session", supportToken())).andExpect(status().isOk());
        mvc.perform(asSupport("/api/v1/auth/session", supportToken())).andExpect(status().isOk());

        assertThat(OwnerDatabase.shared().jdbc().sql("SELECT count(*) FROM audit.audit_event WHERE "
                        + "action = 'SUPPORT_SESSION_STARTED' AND entity_id = ? AND actor_id = ?")
                .param(grant.toString()).param(supportAccount).query(Long.class).single())
                .isEqualTo(1);
        mvc.perform(get("/api/v1/backoffice/alerts").with(TestJwtFactory.account(admin).bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts[?(@.code == 'SUPPORT_ACCESS_ACTIVE')]").isNotEmpty());
    }
}
