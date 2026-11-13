package com.xavier.backend.identity.controller;

import static com.xavier.backend.identity.MfaFixtures.MFA;
import static com.xavier.backend.identity.MfaFixtures.body;
import static com.xavier.backend.identity.MfaFixtures.from;
import static com.xavier.backend.identity.MfaFixtures.loginWithCode;
import static com.xavier.backend.identity.MfaFixtures.reset;
import static com.xavier.backend.identity.MfaFixtures.stepUp;
import static com.xavier.backend.identity.MfaFixtures.verify;
import static com.xavier.backend.identity.MfaFixtures.verifyRecovery;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.MfaFixtures;
import com.xavier.backend.identity.MfaTest;
import com.xavier.backend.identity.MutableClock;
import com.xavier.backend.identity.SwitchableMfaPolicy;
import com.xavier.backend.identity.mfa.Base32;
import com.xavier.backend.identity.mfa.MfaCodes;
import com.xavier.backend.identity.mfa.MfaMode;
import com.xavier.backend.identity.mfa.SecretCipher;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A60 / D143: after every second-factor flow (enrol, confirm, verify with a code and with a recovery code, step-up,
 * regenerate, a wrong code, reset, enrol with an enrolment code), no TOTP secret, recovery code, enrolment code, nor
 * the hash of a code is in {@code audit.audit_event.details}, in {@code shared.idempotency_record}, or in the logs.
 * The 6-digit TOTP codes are left out of the log check: any timestamp or id can contain six digits by chance, and a
 * used code is worthless (replay).
 */
@MfaTest
@ExtendWith(OutputCaptureExtension.class)
class NoSecretLeakTest {

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

    @AfterEach
    void resetMode() {
        policy.reset();
    }

    @Test
    void noFlowLeavesASecretACodeOrAHashBehind(CapturedOutput output) throws Exception {
        List<String> secrets = new ArrayList<>();
        MfaFixtures mfa = new MfaFixtures(mvc, clock);
        IdentityFixtures fixtures = new IdentityFixtures(mvc);
        MfaFixtures.Enrolled admin = mfa.enrolledUser(Set.of(IdentityFixtures.ADMINISTRATOR));
        MfaFixtures.Enrolled user = mfa.enrolledUser(Set.of(IdentityFixtures.WAITER));
        IdentityFixtures.User newcomer = fixtures.user(Set.of(IdentityFixtures.WAITER), null);
        collect(secrets, admin);
        collect(secrets, user);

        policy.set(MfaMode.ALL_BACKOFFICE);
        Cookie pending = pending(user.user());
        mvc.perform(verify(pending, mfa.wrongCode(user.secret()))).andExpect(status().isUnauthorized());
        Cookie session = mvc.perform(verify(pending, mfa.nextCode(user.secret()))).andExpect(status().isOk())
                .andReturn().getResponse().getCookie("SESSION");
        mvc.perform(verifyRecovery(pending(user.user()), user.recoveryCodes().getFirst())).andExpect(status().isOk());
        mvc.perform(stepUp(session, mfa.nextCode(user.secret()))).andExpect(status().isOk());
        body(mvc.perform(post(MFA + "/recovery-codes").cookie(session).with(csrf())).andExpect(status().isOk()))
                .get("recoveryCodes").forEach(code -> secrets.add(code.asString()));

        Cookie adminSession = mvc.perform(verify(pending(admin.user()), mfa.nextCode(admin.secret())))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("SESSION");
        String enrolmentCode = body(mvc.perform(reset(newcomer.id(), "first enrolment").cookie(adminSession))
                .andExpect(status().isOk())).get("enrolmentCode").asString();
        secrets.add(enrolmentCode);
        policy.set(MfaMode.SENSITIVE);
        Cookie remote = mvc.perform(loginWithCode(newcomer.username(), IdentityFixtures.STRONG_PASSWORD,
                        enrolmentCode).with(from(MfaTest.REMOTE_ADDRESS)))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("SESSION");
        String secret = body(mvc.perform(mfa.startEnrolment(remote, enrolmentCode)
                .with(from(MfaTest.REMOTE_ADDRESS))).andExpect(status().isOk())).get("secret").asString();
        secrets.add(secret);
        body(mvc.perform(MfaFixtures.confirm(remote, mfa.nextCode(Base32.decode(secret)))
                .with(from(MfaTest.REMOTE_ADDRESS))).andExpect(status().isOk()))
                .get("recoveryCodes").forEach(code -> secrets.add(code.asString()));

        List<String> forbidden = new ArrayList<>();
        for (String value : secrets) {
            forbidden.add(value);
            forbidden.add(value.replace("-", ""));
            forbidden.add(cipher.codeHash(SecretCipher.CodePurpose.RECOVERY_CODE, value));
            forbidden.add(cipher.codeHash(SecretCipher.CodePurpose.ENROLMENT_CODE, value));
            forbidden.add(RecoveryCodeOnceTest.plainSha256(MfaCodes.normalize(value)));
        }
        String audit = jdbc.sql("SELECT coalesce(string_agg(details::text || ' ' || coalesce(reason, ''), '\n'), '') "
                + "FROM audit.audit_event").query(String.class).single();
        String idempotency = jdbc.sql("SELECT coalesce(string_agg(coalesce(response_body::text, '') || ' ' || key, "
                + "'\n'), '') FROM shared.idempotency_record").query(String.class).single();
        assertThat(audit).contains("STEP_UP", "RECOVERY_CODE"); // the flows were audited
        for (String value : forbidden) {
            assertThat(audit).as("audit details").doesNotContain(value);
            assertThat(idempotency).as("idempotency records").doesNotContain(value);
            assertThat(output.getAll()).as("logs").doesNotContain(value);
        }
    }

    private static void collect(List<String> secrets, MfaFixtures.Enrolled enrolled) {
        secrets.add(Base32.encode(enrolled.secret()).replace("=", ""));
        secrets.addAll(enrolled.recoveryCodes());
    }

    private Cookie pending(IdentityFixtures.User user) throws Exception {
        return mvc.perform(IdentityFixtures.loginRequest(user.username(), IdentityFixtures.STRONG_PASSWORD))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("SESSION");
    }
}
