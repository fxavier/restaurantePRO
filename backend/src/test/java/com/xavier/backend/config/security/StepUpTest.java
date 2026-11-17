package com.xavier.backend.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.access.Permission;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * A87, D184 (RFC 9470): a sensitive permission (here {@code SUPPORT_ACCESS_MANAGE}, granting support access) needs a
 * token with a second factor ({@code acr ≥ 2}) whose {@code auth_time} is at most five minutes old. Otherwise 401
 * with the challenge {@code WWW-Authenticate: Bearer error="insufficient_user_authentication", ...,
 * acr_values="2", max_age=300} and {@code MFA_STEP_UP_REQUIRED}; after the step-up, the same request with the same
 * {@code Idempotency-Key} runs once (the 401 was never stored, D47). A non-sensitive permission needs no step-up.
 */
@SecurityHttpTest
class StepUpTest {

    static final String CHALLENGE = "Bearer error=\"insufficient_user_authentication\", "
            + "error_description=\"A different authentication level is required\", acr_values=\"2\", max_age=300";

    @Autowired
    MockMvc mvc;

    UUID tenant;
    String subject;
    UUID supportAccount;
    String supporter;

    @BeforeEach
    void anAdministratorAndASupportAccount() {
        tenant = Members.activeTenant();
        subject = "kc-" + UUID.randomUUID();
        Members.member(tenant, subject, Members.role(tenant, Permission.SUPPORT_ACCESS_MANAGE,
                Permission.USER_MANAGE));
        supportAccount = Members.account(tenant, true);
        supporter = "ops-" + UUID.randomUUID();
    }

    private ResultActions grant(TestJwtFactory token, String key) throws Exception {
        return mvc.perform(post("/api/v1/backoffice/support-grants").with(token.bearer())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"accountId": "%s", "subject": "%s", "durationMinutes": 60, "reason": "ticket 7"}"""
                        .formatted(supportAccount, supporter)));
    }

    @Test
    void aPasswordOnlyTokenGetsTheChallenge() throws Exception {
        grant(TestJwtFactory.account(subject).authenticated("1", Instant.now()), UUID.randomUUID().toString())
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", CHALLENGE))
                .andExpect(jsonPath("$.code").value("MFA_STEP_UP_REQUIRED"));
    }

    @Test
    void aSecondFactorOlderThanTheMaxAgeGetsTheChallenge() throws Exception {
        grant(TestJwtFactory.account(subject).authenticated("2", Instant.now().minusSeconds(301)),
                UUID.randomUUID().toString())
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", CHALLENGE));
    }

    @Test
    void aTokenWithoutAuthTimeGetsTheChallenge() throws Exception {
        grant(TestJwtFactory.account(subject).without("auth_time"), UUID.randomUUID().toString())
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", CHALLENGE));
    }

    @Test
    void afterTheStepUpTheSameKeyRunsOnce() throws Exception {
        String key = UUID.randomUUID().toString();
        grant(TestJwtFactory.account(subject).authenticated("1", Instant.now()), key)
                .andExpect(status().isUnauthorized());

        String first = grant(TestJwtFactory.account(subject), key).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String replay = grant(TestJwtFactory.account(subject), key).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(replay).isEqualTo(first);
        assertThat(OwnerDatabase.shared().jdbc().sql("SELECT count(*) FROM identity.support_grant "
                + "WHERE tenant_id = ? AND account_id = ?").param(tenant).param(supportAccount)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aNonSensitivePermissionNeedsNoStepUp() throws Exception {
        UUID viewer = Members.activeTenant();
        String reader = "kc-" + UUID.randomUUID();
        Members.member(viewer, reader, Members.role(viewer, Permission.CATALOG_MANAGE));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/backoffice/catalog/families")
                        .with(TestJwtFactory.account(reader).authenticated("1", Instant.now().minusSeconds(3600))
                                .bearer()))
                .andExpect(status().isOk());
    }
}
