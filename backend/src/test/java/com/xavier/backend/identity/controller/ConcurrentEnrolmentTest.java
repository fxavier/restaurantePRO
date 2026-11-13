package com.xavier.backend.identity.controller;

import static com.xavier.backend.identity.MfaFixtures.confirm;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.MfaFixtures;
import com.xavier.backend.identity.MfaTest;
import com.xavier.backend.identity.MutableClock;
import com.xavier.backend.identity.Races;
import com.xavier.backend.identity.mfa.Base32;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * D143: one PENDING and one ACTIVE factor per account, also when two sessions of the account enrol at the same time
 * (serialized on the account row; the partial unique indexes are the backstop).
 */
@MfaTest
class ConcurrentEnrolmentTest {

    static final String LOCK_ACCOUNT = "SELECT id FROM identity.user_account WHERE id = ? FOR UPDATE";

    @Autowired
    MockMvc mvc;

    @Autowired
    MutableClock clock;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    IdentityFixtures fixtures;
    MfaFixtures mfa;
    IdentityFixtures.User user;
    Cookie first;
    Cookie second;

    @BeforeEach
    void twoSessions() throws Exception {
        fixtures = new IdentityFixtures(mvc);
        mfa = new MfaFixtures(mvc, clock);
        user = fixtures.user(Set.of(IdentityFixtures.WAITER), null);
        first = fixtures.login(user.username(), IdentityFixtures.STRONG_PASSWORD);
        second = fixtures.login(user.username(), IdentityFixtures.STRONG_PASSWORD);
    }

    @Test
    void twoEnrolmentsAtOnceLeaveOnePendingFactor() throws Exception {
        List<Integer> statuses = Races.behindLock(transactions, jdbc, LOCK_ACCOUNT, user.id(), List.of(
                statusOf(() -> mvc.perform(mfa.startEnrolment(first, null))),
                statusOf(() -> mvc.perform(mfa.startEnrolment(second, null)))));

        assertThat(statuses).containsExactly(200, 200);
        assertThat(factors("PENDING")).isEqualTo(1);
    }

    @Test
    void twoConfirmationsAtOnceActivateOneFactor() throws Exception {
        byte[] secret = Base32.decode(MfaFixtures.body(mvc.perform(mfa.startEnrolment(first, null))
                .andExpect(status().isOk())).get("secret").asString());
        String code = mfa.nextCode(secret);

        List<Integer> statuses = Races.behindLock(transactions, jdbc, LOCK_ACCOUNT, user.id(), List.of(
                statusOf(() -> mvc.perform(confirm(first, code))),
                statusOf(() -> mvc.perform(confirm(second, code)))));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(factors("ACTIVE")).isEqualTo(1);
        assertThat(factors("PENDING")).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM identity.mfa_recovery_code c JOIN identity.mfa_factor f "
                + "ON f.id = c.factor_id WHERE f.account_id = ?").param(user.id()).query(Long.class).single())
                .isEqualTo(10);
    }

    @Test
    void anEnrolledAccountDoesNotEnrolAgain() throws Exception {
        mfa.enrol(user, first);

        mvc.perform(mfa.startEnrolment(second, null)).andExpect(status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("MFA_ALREADY_ENROLLED"));
    }

    @Test
    void theDatabaseRefusesASecondActiveOrPendingFactor() throws Exception {
        mfa.enrol(user, first);
        mvc.perform(mfa.startEnrolment(second, null)).andExpect(status().isConflict());

        assertThatThrownBy(() -> insertFactor("ACTIVE")).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("mfa_factor_one_active_uk");
        insertFactor("PENDING");
        assertThatThrownBy(() -> insertFactor("PENDING")).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("mfa_factor_one_pending_uk");
    }

    @Test
    void theStatusChecksHoldInTheDatabase() {
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO identity.mfa_factor (id, account_id, kind, status, secret_ciphertext, secret_nonce,
                            key_id, created_at, version)
                        VALUES (?, ?, 'TOTP', 'ACTIVE', '\\x00', '\\x000000000000000000000000', 'k', now(), 0)""")
                .param(UUID.randomUUID()).param(user.id()).update())
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("mfa_factor_active_ck");
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO identity.mfa_factor (id, account_id, kind, status, secret_ciphertext, secret_nonce,
                            key_id, created_at, version)
                        VALUES (?, ?, 'TOTP', 'PENDING', '\\x00', '\\x000000000000000000000000', 'k', now(), 0)""")
                .param(UUID.randomUUID()).param(user.id()).update())
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("mfa_factor_pending_ck");
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO identity.mfa_factor (id, account_id, kind, status, secret_ciphertext, secret_nonce,
                            key_id, created_at, activated_at, failed_attempts, version)
                        VALUES (?, ?, 'TOTP', 'REVOKED', '\\x00', '\\x000000000000000000000000', 'k', now(), now(), 0,
                            0)""")
                .param(UUID.randomUUID()).param(user.id()).update())
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("mfa_factor_revoked_ck");
    }

    private void insertFactor(String status) {
        jdbc.sql("""
                        INSERT INTO identity.mfa_factor (id, account_id, kind, status, secret_ciphertext, secret_nonce,
                            key_id, created_at, activated_at, pending_expires_at, version)
                        VALUES (?, ?, 'TOTP', ?, '\\x00', '\\x000000000000000000000000', 'k', now(),
                            CASE WHEN ? = 'ACTIVE' THEN now() END, CASE WHEN ? = 'PENDING' THEN now() END, 0)""")
                .param(UUID.randomUUID()).param(user.id()).param(status).param(status).param(status).update();
    }

    private long factors(String status) {
        return jdbc.sql("SELECT count(*) FROM identity.mfa_factor WHERE account_id = ? AND status = ?")
                .param(user.id()).param(status).query(Long.class).single();
    }

    private static Callable<Integer> statusOf(Callable<org.springframework.test.web.servlet.ResultActions> request) {
        return () -> request.call().andReturn().getResponse().getStatus();
    }
}
