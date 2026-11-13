package com.xavier.backend.identity.controller;

import static com.xavier.backend.identity.MfaFixtures.MFA;
import static com.xavier.backend.identity.MfaFixtures.body;
import static com.xavier.backend.identity.MfaFixtures.verifyRecovery;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.MfaFixtures;
import com.xavier.backend.identity.MfaTest;
import com.xavier.backend.identity.MutableClock;
import com.xavier.backend.identity.SwitchableMfaPolicy;
import com.xavier.backend.identity.mfa.MfaMode;
import com.xavier.backend.identity.mfa.SecretCipher;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** D139 / A57: a recovery code works once; regenerating replaces them all and needs a fresh step-up. */
@MfaTest
class RecoveryCodeOnceTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    MutableClock clock;

    @Autowired
    SwitchableMfaPolicy policy;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    SecretCipher cipher;

    MfaFixtures mfa;
    MfaFixtures.Enrolled enrolled;

    MfaFixtures.Enrolled other;

    @BeforeEach
    void enrolled() throws Exception {
        mfa = new MfaFixtures(mvc, clock);
        enrolled = mfa.enrolledUser(Set.of(IdentityFixtures.WAITER));
        other = mfa.enrolledUser(Set.of(IdentityFixtures.WAITER));
        policy.set(MfaMode.ALL_BACKOFFICE);
    }

    @AfterEach
    void reset() {
        policy.reset();
    }

    @Test
    void aRecoveryCodeLogsInOnceInAnyCaseAndWithoutItsDash() throws Exception {
        String code = enrolled.recoveryCodes().getFirst();

        mvc.perform(verifyRecovery(pending(), code.replace("-", "").toLowerCase()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mfa.state").value("VERIFIED"));
        mvc.perform(verifyRecovery(pending(), code))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("MFA_INVALID_CODE"));

        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE action = 'MFA_RECOVERY_CODE_USED' "
                + "AND entity_id = ? AND (details ->> 'recoveryCodesLeft')::int = 9")
                .param(enrolled.user().id().toString()).query(Long.class).single()).isEqualTo(1);
    }

    /** D139 amended (S56 review): a dump alone cannot test guesses; the hash needs the MFA key. */
    @Test
    void storedHashesAreKeyedAndNameTheirKey() throws Exception {
        String code = enrolled.recoveryCodes().getFirst();
        List<java.util.Map<String, Object>> rows = jdbc.sql("""
                        SELECT c.code_hash, c.key_id FROM identity.mfa_recovery_code c
                        JOIN identity.mfa_factor f ON f.id = c.factor_id
                        WHERE f.account_id = ? AND f.status = 'ACTIVE'""")
                .param(enrolled.user().id()).query().listOfRows();

        assertThat(rows).hasSize(10).allSatisfy(row -> assertThat(row.get("key_id")).isEqualTo(cipher.keyId()));
        assertThat(rows).extracting(row -> ((String) row.get("code_hash")).strip())
                .contains(cipher.codeHash(SecretCipher.CodePurpose.RECOVERY_CODE, code))
                .doesNotContain(plainSha256(code.replace("-", "")), plainSha256(code));
    }

    static String plainSha256(String text) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void anotherAccountsRecoveryCodeDoesNotWork() throws Exception {
        mvc.perform(verifyRecovery(pending(), other.recoveryCodes().getFirst()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void regeneratingNeedsAFreshStepUpAndReplacesEveryCode() throws Exception {
        Cookie session = enrolled.session(); // VERIFIED at confirmation
        clock.advance(Duration.ofMinutes(16));
        mvc.perform(regenerate(session)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_STEP_UP_REQUIRED"));

        mvc.perform(MfaFixtures.stepUp(session, mfa.nextCode(enrolled.secret()))).andExpect(status().isOk());
        List<String> fresh = new ArrayList<>();
        body(mvc.perform(regenerate(session)).andExpect(status().isOk())).get("recoveryCodes")
                .forEach(c -> fresh.add(c.asString()));

        assertThat(fresh).hasSize(10).doesNotContainAnyElementsOf(enrolled.recoveryCodes());
        mvc.perform(verifyRecovery(pending(), enrolled.recoveryCodes().get(1))).andExpect(status().isUnauthorized());
        mvc.perform(verifyRecovery(pending(), fresh.getFirst())).andExpect(status().isOk());
    }

    @Test
    void regeneratingWithoutAFactorIsNotEnrolled() throws Exception {
        policy.reset();
        IdentityFixtures.User user = new IdentityFixtures(mvc).user(Set.of(IdentityFixtures.WAITER), null);
        Cookie session = new IdentityFixtures(mvc).login(user.username(), IdentityFixtures.STRONG_PASSWORD);

        mvc.perform(regenerate(session)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MFA_NOT_ENROLLED"));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder regenerate(
            Cookie session) {
        return post(MFA + "/recovery-codes").cookie(session).with(csrf());
    }

    private Cookie pending() throws Exception {
        return mvc.perform(IdentityFixtures.loginRequest(enrolled.user().username(), IdentityFixtures.STRONG_PASSWORD))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("SESSION");
    }
}
