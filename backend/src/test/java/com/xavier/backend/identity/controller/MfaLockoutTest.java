package com.xavier.backend.identity.controller;

import static com.xavier.backend.identity.MfaFixtures.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

/** D139 / A57: 5 consecutive wrong codes lock second-factor checks for 15 minutes (429), audited once. */
@MfaTest
class MfaLockoutTest {

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
    void enrolled() throws Exception {
        mfa = new MfaFixtures(mvc, clock);
        enrolled = mfa.enrolledUser(Set.of(IdentityFixtures.WAITER));
        policy.set(MfaMode.ALL_BACKOFFICE);
    }

    @AfterEach
    void reset() {
        policy.reset();
    }

    @Test
    void fourWrongCodesStillLetTheRightOneIn() throws Exception {
        Cookie pending = pending();
        for (int i = 0; i < 4; i++) {
            wrong(pending).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("MFA_INVALID_CODE"));
        }

        mvc.perform(verify(pending, mfa.nextCode(enrolled.secret()))).andExpect(status().isOk());
    }

    @Test
    void theFifthWrongCodeLocksForFifteenMinutesAndEndsThePendingLogin() throws Exception {
        Cookie pending = pending();
        for (int i = 0; i < 4; i++) {
            wrong(pending).andExpect(status().isUnauthorized());
        }

        wrong(pending).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("MFA_LOCKED"))
                .andExpect(header().string("Retry-After", "900"));
        mvc.perform(verify(pending, mfa.currentCode(enrolled.secret())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MFA_NOT_PENDING"));

        mvc.perform(verify(pending(), mfa.nextCode(enrolled.secret())))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("MFA_LOCKED"));
        assertThat(locks()).isEqualTo(1);

        clock.advance(Duration.ofMinutes(15));
        mvc.perform(verify(pending(), mfa.nextCode(enrolled.secret()))).andExpect(status().isOk());
        assertThat(locks()).isEqualTo(1);
    }

    private org.springframework.test.web.servlet.ResultActions wrong(Cookie pending) throws Exception {
        return mvc.perform(verify(pending, mfa.wrongCode(enrolled.secret())));
    }

    private long locks() {
        return jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE action = 'MFA_LOCKED' AND entity_id = ?")
                .param(enrolled.user().id().toString()).query(Long.class).single();
    }

    private Cookie pending() throws Exception {
        return mvc.perform(IdentityFixtures.loginRequest(enrolled.user().username(), IdentityFixtures.STRONG_PASSWORD))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mfa.state").value("PENDING"))
                .andReturn().getResponse().getCookie("SESSION");
    }
}
