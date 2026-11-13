package com.xavier.backend.identity.controller;

import static com.xavier.backend.identity.MfaFixtures.MFA;
import static com.xavier.backend.identity.MfaFixtures.body;
import static com.xavier.backend.identity.MfaFixtures.verify;
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
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/** A57: mode ALL_BACKOFFICE, an enrolled account logs in through a pending session that holds no authority. */
@MfaTest
class MfaLoginFlowTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    MutableClock clock;

    @Autowired
    SwitchableMfaPolicy policy;

    @Autowired
    JdbcClient jdbc;

    MfaFixtures mfa;
    MfaFixtures.Enrolled enrolled;

    @BeforeEach
    void enrolledAdministrator() throws Exception {
        mfa = new MfaFixtures(mvc, clock);
        enrolled = mfa.enrolledUser(Set.of(IdentityFixtures.ADMINISTRATOR));
        policy.set(MfaMode.ALL_BACKOFFICE);
    }

    @AfterEach
    void reset() {
        policy.reset();
    }

    @Test
    void aLoginIsPendingAndHoldsNoAuthorityUntilAValidCode() throws Exception {
        var login = mvc.perform(IdentityFixtures.loginRequest(enrolled.user().username(),
                        IdentityFixtures.STRONG_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfa.state").value("PENDING"))
                .andExpect(jsonPath("$.permissions").isEmpty())
                .andReturn().getResponse();
        Cookie pending = login.getCookie("SESSION");

        mvc.perform(get("/api/v1/backoffice/users").cookie(pending)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/auth/session").cookie(pending))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfa.state").value("PENDING"))
                .andExpect(jsonPath("$.permissions").isEmpty());

        var verified = mvc.perform(verify(pending, mfa.nextCode(enrolled.secret())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfa.state").value("VERIFIED"))
                .andExpect(jsonPath("$.mfa.stepUpValidUntil").isNotEmpty())
                .andExpect(jsonPath("$.permissions").isNotEmpty())
                .andReturn().getResponse();
        Cookie session = verified.getCookie("SESSION");

        assertThat(session.getValue()).isNotEqualTo(pending.getValue());
        mvc.perform(get("/api/v1/backoffice/users").cookie(session)).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE action = 'MFA_VERIFIED' AND entity_id = ? "
                        + "AND details ->> 'kind' = 'LOGIN' AND details ->> 'method' = 'TOTP'")
                .param(enrolled.user().id().toString()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void theSameCodeAgainIsRefused() throws Exception {
        String code = mfa.nextCode(enrolled.secret());
        mvc.perform(verify(pendingLogin(), code)).andExpect(status().isOk());

        mvc.perform(verify(pendingLogin(), code))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MFA_INVALID_CODE"));
    }

    @Test
    void aPendingLoginExpiresAfterFiveMinutesAndEnds() throws Exception {
        Cookie pending = pendingLogin();
        clock.advance(Duration.ofMinutes(5));

        mvc.perform(verify(pending, mfa.currentCode(enrolled.secret())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MFA_PENDING_EXPIRED"));
        mvc.perform(verify(pending, mfa.nextCode(enrolled.secret())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MFA_NOT_PENDING"));
    }

    @Test
    void justBeforeFiveMinutesItIsStillPending() throws Exception {
        Cookie pending = pendingLogin();
        clock.advance(Duration.ofMinutes(5).minusSeconds(1));

        mvc.perform(verify(pending, mfa.currentCode(enrolled.secret()))).andExpect(status().isOk());
    }

    @Test
    void aVerifiedSessionIsNotPending() throws Exception {
        Cookie session = verifiedLogin();

        mvc.perform(verify(session, mfa.nextCode(enrolled.secret())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MFA_NOT_PENDING"));
    }

    @Test
    void aPendingSessionCannotChangeThePassword() throws Exception {
        Cookie pending = pendingLogin();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/password")
                        .cookie(pending)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(IdentityFixtures.json("currentPassword", IdentityFixtures.STRONG_PASSWORD,
                                "newPassword", "another strong passphrase")))
                .andExpect(status().isForbidden());
    }

    @Test
    void theStatusShowsTheModeAndTheFactor() throws Exception {
        JsonNode status = body(mvc.perform(get(MFA).cookie(verifiedLogin())).andExpect(status().isOk()));

        assertThat(status.get("mode").asString()).isEqualTo("ALL_BACKOFFICE");
        assertThat(status.get("enrolled").asBoolean()).isTrue();
        assertThat(status.get("recoveryCodesLeft").asLong()).isEqualTo(10);
        assertThat(status.get("stepUpValidUntil").isNull()).isFalse();
    }

    private Cookie pendingLogin() throws Exception {
        return mvc.perform(IdentityFixtures.loginRequest(enrolled.user().username(), IdentityFixtures.STRONG_PASSWORD))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mfa.state").value("PENDING"))
                .andReturn().getResponse().getCookie("SESSION");
    }

    private Cookie verifiedLogin() throws Exception {
        return mvc.perform(verify(pendingLogin(), mfa.nextCode(enrolled.secret()))).andExpect(status().isOk())
                .andReturn().getResponse().getCookie("SESSION");
    }
}
