package com.xavier.backend.identity.controller;

import static com.xavier.backend.identity.MfaFixtures.asAdministrator;
import static com.xavier.backend.identity.MfaFixtures.reset;
import static com.xavier.backend.identity.MfaFixtures.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.MfaFixtures;
import com.xavier.backend.identity.MfaTest;
import com.xavier.backend.identity.MutableClock;
import com.xavier.backend.identity.Races;
import com.xavier.backend.identity.SwitchableMfaPolicy;
import com.xavier.backend.identity.mfa.MfaMode;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * D143: an administrator's reset while the user is completing a pending login. Whatever order they run in, the factor
 * ends REVOKED and no session of the user survives, including one a verify may have just started. The reset is run
 * with step-up off: what is tested is the reset, not the administrator's step-up (StepUpTest, MfaResetTest).
 */
@MfaTest
class MfaResetDuringVerifyTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    MutableClock clock;

    @Autowired
    SwitchableMfaPolicy policy;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    MfaFixtures mfa;
    IdentityFixtures.User administrator;
    MfaFixtures.Enrolled user;

    @BeforeEach
    void accounts() throws Exception {
        mfa = new MfaFixtures(mvc, clock);
        administrator = new IdentityFixtures(mvc).user(Set.of(IdentityFixtures.ADMINISTRATOR), null);
        user = mfa.enrolledUser(Set.of(IdentityFixtures.WAITER));
    }

    @AfterEach
    void resetMode() {
        policy.reset();
    }

    @Test
    void aResetEndsAPendingLoginBeforeItIsVerified() throws Exception {
        policy.set(MfaMode.ALL_BACKOFFICE);
        Cookie pending = pending();
        policy.reset();
        mvc.perform(reset(user.user().id(), "lost phone").with(asAdministrator(administrator.id())))
                .andExpect(status().isOk());

        mvc.perform(verify(pending, mfa.nextCode(user.secret()))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MFA_NOT_PENDING"));
        assertThat(verifications()).isZero();
    }

    @Test
    void aResetRacingAVerifyLeavesNoSessionAndARevokedFactor() throws Exception {
        policy.set(MfaMode.ALL_BACKOFFICE);
        Cookie pending = pending();
        String code = mfa.nextCode(user.secret());
        policy.reset(); // the administrator's request; the user's verify does not depend on the mode

        List<MockHttpServletResponse> responses = Races.behindLock(transactions, jdbc,
                "SELECT id FROM identity.mfa_factor WHERE account_id = ? AND status = 'ACTIVE' FOR UPDATE",
                user.user().id(), List.<Callable<MockHttpServletResponse>>of(
                        () -> mvc.perform(verify(pending, code)).andReturn().getResponse(),
                        () -> mvc.perform(reset(user.user().id(), "lost phone")
                                .with(asAdministrator(administrator.id()))).andReturn().getResponse()));

        assertThat(responses.get(1).getStatus()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT status FROM identity.mfa_factor WHERE account_id = ? AND status <> 'PENDING'")
                .param(user.user().id()).query(String.class).single()).isEqualTo("REVOKED");
        Cookie afterVerify = responses.get(0).getCookie("SESSION");
        for (Cookie session : new Cookie[] {pending, afterVerify}) {
            if (session != null) {
                mvc.perform(get("/api/v1/auth/session").cookie(session)).andExpect(status().isUnauthorized());
            }
        }
    }

    private long verifications() {
        return jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE action = 'MFA_VERIFIED' AND entity_id = ? "
                + "AND details ->> 'kind' = 'LOGIN'").param(user.user().id().toString()).query(Long.class).single();
    }

    private Cookie pending() throws Exception {
        return mvc.perform(IdentityFixtures.loginRequest(user.user().username(), IdentityFixtures.STRONG_PASSWORD))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mfa.state").value("PENDING"))
                .andReturn().getResponse().getCookie("SESSION");
    }
}
