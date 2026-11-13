package com.xavier.backend.identity.controller;

import static com.xavier.backend.identity.MfaFixtures.from;
import static com.xavier.backend.identity.MfaFixtures.loginWithCode;
import static com.xavier.backend.identity.MfaTest.REMOTE_ADDRESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.IdentityFixtures.User;
import com.xavier.backend.identity.MfaFixtures;
import com.xavier.backend.identity.MfaTest;
import com.xavier.backend.identity.MutableClock;
import com.xavier.backend.identity.SwitchableMfaPolicy;
import com.xavier.backend.identity.mfa.MfaMode;
import com.xavier.backend.identity.mfa.SecretCipher;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/** D143 / A60: an enrolment code works once, for its own account, within 24 hours. */
@MfaTest
class EnrolmentCodeOnceTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    MutableClock clock;

    @Autowired
    SwitchableMfaPolicy policy;

    @Autowired
    org.springframework.jdbc.core.simple.JdbcClient jdbc;

    @Autowired
    SecretCipher cipher;

    IdentityFixtures fixtures;
    MfaFixtures mfa;
    User administrator;
    User user;
    String code;

    @BeforeEach
    void codeIssued() throws Exception {
        fixtures = new IdentityFixtures(mvc);
        mfa = new MfaFixtures(mvc, clock);
        administrator = fixtures.user(Set.of(IdentityFixtures.ADMINISTRATOR), null);
        user = fixtures.user(Set.of(IdentityFixtures.WAITER), null);
        code = mfa.resetBy(administrator.id(), user.id());
        policy.set(MfaMode.SENSITIVE);
    }

    @AfterEach
    void reset() {
        policy.reset();
    }

    @Test
    void theCodeStartsOneEnrolmentOnly() throws Exception {
        Cookie session = remoteLogin(user, code).andExpect(status().isOk()).andReturn().getResponse()
                .getCookie("SESSION");

        mvc.perform(mfa.startEnrolment(session, code).with(from(REMOTE_ADDRESS))).andExpect(status().isOk());
        mvc.perform(mfa.startEnrolment(session, code).with(from(REMOTE_ADDRESS)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("MFA_ENROLMENT_CODE_INVALID"));
        remoteLogin(user, code).andExpect(status().isUnprocessableContent());
    }

    /** D139 amended (S56 review): the enrolment code is stored as a keyed hash with its key id. */
    @Test
    void theStoredHashIsKeyed() throws Exception {
        java.util.Map<String, Object> row = jdbc.sql("SELECT code_hash, key_id FROM identity.mfa_enrolment_code "
                + "WHERE account_id = ? AND used_at IS NULL").param(user.id()).query().singleRow();

        assertThat(row.get("key_id")).isEqualTo(cipher.keyId());
        assertThat(((String) row.get("code_hash")).strip())
                .isEqualTo(cipher.codeHash(SecretCipher.CodePurpose.ENROLMENT_CODE, code))
                .isNotEqualTo(RecoveryCodeOnceTest.plainSha256(code.replace("-", "")));
    }

    @Test
    void theCodeExpiresAfterTwentyFourHours() throws Exception {
        clock.advance(Duration.ofHours(24).minusSeconds(1));
        Cookie session = remoteLogin(user, code).andExpect(status().isOk()).andReturn().getResponse()
                .getCookie("SESSION");
        clock.advance(Duration.ofSeconds(1));

        remoteLogin(user, code).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("MFA_ENROLMENT_CODE_INVALID"));
        mvc.perform(mfa.startEnrolment(session, code).with(from(REMOTE_ADDRESS)))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void anotherAccountsCodeDoesNotWork() throws Exception {
        policy.reset();
        User other = fixtures.user(Set.of(IdentityFixtures.WAITER), null);
        mfa.resetBy(administrator.id(), other.id());
        policy.set(MfaMode.SENSITIVE);

        remoteLogin(other, code).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("MFA_ENROLMENT_CODE_INVALID"));
    }

    @Test
    void aWrongPasswordDoesNotStartAnEnrolmentNorUseTheCode() throws Exception {
        Cookie session = remoteLogin(user, code).andReturn().getResponse().getCookie("SESSION");

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(MfaFixtures.MFA
                                + "/enrolment").cookie(session)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .csrf()).with(from(REMOTE_ADDRESS))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(IdentityFixtures.json("currentPassword", "not the password", "enrolmentCode", code)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("WRONG_CURRENT_PASSWORD"));
        mvc.perform(mfa.startEnrolment(session, code).with(from(REMOTE_ADDRESS))).andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions remoteLogin(User account, String enrolmentCode)
            throws Exception {
        return mvc.perform(loginWithCode(account.username(), IdentityFixtures.STRONG_PASSWORD, enrolmentCode)
                .with(from(REMOTE_ADDRESS)));
    }
}
