package com.xavier.backend.identity.controller;

import static com.xavier.backend.identity.MfaFixtures.from;
import static com.xavier.backend.identity.MfaFixtures.reset;
import static com.xavier.backend.identity.MfaFixtures.verifyRecovery;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.MfaFixtures;
import com.xavier.backend.identity.MfaTest;
import com.xavier.backend.identity.MutableClock;
import com.xavier.backend.identity.SwitchableMfaPolicy;
import com.xavier.backend.identity.mfa.MfaMode;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * A60 (D143): an administrator, stepped up, resets a user's second factor with a reason: the factor and its recovery
 * codes stop working, the user's sessions end, an enrolment code is issued for 24 hours, and the reset is audited with
 * its reason; never on one's own account.
 */
@MfaTest
class MfaResetTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    MutableClock clock;

    @Autowired
    SwitchableMfaPolicy policy;

    @Autowired
    JdbcClient jdbc;

    MfaFixtures mfa;
    MfaFixtures.Enrolled admin;
    MfaFixtures.Enrolled user;
    Cookie adminSession;

    @BeforeEach
    void accounts() throws Exception {
        mfa = new MfaFixtures(mvc, clock);
        admin = mfa.enrolledUser(Set.of(IdentityFixtures.ADMINISTRATOR));
        user = mfa.enrolledUser(Set.of(IdentityFixtures.WAITER));
        adminSession = mfa.login(admin.user().username(), MfaTest.LAN_ADDRESS);
        policy.set(MfaMode.SENSITIVE);
        mvc.perform(MfaFixtures.stepUp(adminSession, mfa.nextCode(admin.secret()))).andExpect(status().isOk());
    }

    @AfterEach
    void resetMode() {
        policy.reset();
    }

    @Test
    void aResetRevokesTheFactorEndsTheSessionsAndIssuesAnEnrolmentCode() throws Exception {
        Cookie userSession = mfa.login(user.user().username(), MfaTest.LAN_ADDRESS);
        mvc.perform(get("/api/v1/backoffice/users/" + user.user().id()).cookie(adminSession))
                .andExpect(jsonPath("$.mfaEnrolled").value(true));
        Instant now = clock.instant();

        JsonNode issued = MfaFixtures.body(mvc.perform(reset(user.user().id(), "lost phone").cookie(adminSession))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Cache-Control", "no-store")));

        assertThat(issued.get("enrolmentCode").asString()).matches("[A-Z2-7]{4}-[A-Z2-7]{4}-[A-Z2-7]{4}");
        assertThat(Instant.parse(issued.get("expiresAt").asString())).isEqualTo(now.plus(Duration.ofHours(24)));
        mvc.perform(get("/api/v1/auth/session").cookie(userSession)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/session").cookie(user.session())).andExpect(status().isUnauthorized());
        Map<String, Object> factor = jdbc.sql("SELECT status, revoked_by_account_id, revoke_reason FROM "
                + "identity.mfa_factor WHERE account_id = ? AND status <> 'PENDING'").param(user.user().id())
                .query().singleRow();
        assertThat(factor).containsEntry("status", "REVOKED").containsEntry("revoked_by_account_id",
                admin.user().id()).containsEntry("revoke_reason", "lost phone");
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE action = 'MFA_RESET' AND entity_id = ? "
                        + "AND actor_id = ? AND reason = 'lost phone'")
                .param(user.user().id().toString()).param(admin.user().id()).query(Long.class).single())
                .isEqualTo(1);
        mvc.perform(get("/api/v1/backoffice/users/" + user.user().id() + "/mfa").cookie(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrolled").value(false))
                .andExpect(jsonPath("$.enrolmentCodeExpiresAt").isNotEmpty());
        mvc.perform(get("/api/v1/backoffice/users/" + user.user().id()).cookie(adminSession))
                .andExpect(jsonPath("$.mfaEnrolled").value(false));
    }

    @Test
    void aRecoveryCodeDiesWithItsFactor() throws Exception {
        mvc.perform(reset(user.user().id(), "lost phone").cookie(adminSession)).andExpect(status().isOk());
        policy.set(MfaMode.ALL_BACKOFFICE);

        Cookie session = mvc.perform(IdentityFixtures.loginRequest(user.user().username(),
                        IdentityFixtures.STRONG_PASSWORD).with(from(MfaTest.LAN_ADDRESS)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mfa.state").value("ENROLMENT_REQUIRED"))
                .andReturn().getResponse().getCookie("SESSION");
        mvc.perform(verifyRecovery(session, user.recoveryCodes().getFirst())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MFA_NOT_PENDING"));
    }

    @Test
    void anAdministratorDoesNotResetTheirOwnFactor() throws Exception {
        mvc.perform(reset(admin.user().id(), "lost phone").cookie(adminSession))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("MFA_SELF_RESET"));
    }

    @Test
    void aResetNeedsAReason() throws Exception {
        mvc.perform(reset(user.user().id(), "  ").cookie(adminSession)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
        mvc.perform(reset(user.user().id(), null).cookie(adminSession)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
    }

    @Test
    void anUnknownAccountIsNotFound() throws Exception {
        mvc.perform(reset(UUID.randomUUID(), "lost phone").cookie(adminSession)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/backoffice/users/" + UUID.randomUUID() + "/mfa").cookie(adminSession))
                .andExpect(status().isNotFound());
    }

    @Test
    void aResetIsItselfASensitiveAction() throws Exception {
        Cookie notSteppedUp = mfa.login(admin.user().username(), MfaTest.LAN_ADDRESS);

        mvc.perform(reset(user.user().id(), "lost phone").cookie(notSteppedUp)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_STEP_UP_REQUIRED"));
        assertThat(jdbc.sql("SELECT count(*) FROM identity.mfa_factor WHERE account_id = ? AND status = 'ACTIVE'")
                .param(user.user().id()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aSecondResetVoidsTheFirstCode() throws Exception {
        String first = MfaFixtures.body(mvc.perform(reset(user.user().id(), "lost phone").cookie(adminSession))
                .andExpect(status().isOk())).get("enrolmentCode").asString();
        String second = MfaFixtures.body(mvc.perform(reset(user.user().id(), "again").cookie(adminSession))
                .andExpect(status().isOk())).get("enrolmentCode").asString();

        mvc.perform(MfaFixtures.loginWithCode(user.user().username(), IdentityFixtures.STRONG_PASSWORD, first)
                        .with(from(MfaTest.REMOTE_ADDRESS)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("MFA_ENROLMENT_CODE_INVALID"));
        mvc.perform(MfaFixtures.loginWithCode(user.user().username(), IdentityFixtures.STRONG_PASSWORD, second)
                        .with(from(MfaTest.REMOTE_ADDRESS)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mfa.state").value("ENROLMENT_REQUIRED"));
    }
}
