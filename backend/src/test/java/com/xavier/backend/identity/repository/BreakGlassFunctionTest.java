package com.xavier.backend.identity.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.MfaFixtures;
import com.xavier.backend.identity.MfaTest;
import com.xavier.backend.identity.MutableClock;
import com.xavier.backend.identity.SwitchableMfaPolicy;
import com.xavier.backend.identity.mfa.MfaMode;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * D143 break-glass: {@code deploy/mfa-break-glass.sh} runs {@code identity.mfa_break_glass_reset},
 * {@code shared.end_account_sessions} and {@code audit.record_console_event} in one transaction. Here the same three
 * statements run in one JDBC transaction: the factor is revoked, the account's sessions end, and exactly one system
 * audit row is written, or nothing happens. None of the three functions is executable by PUBLIC.
 */
@MfaTest
class BreakGlassFunctionTest {

    static final String RESET = "SELECT identity.mfa_break_glass_reset(?, ?)";
    static final String SESSIONS = "SELECT shared.end_account_sessions(?)";
    static final String AUDIT = "SELECT audit.record_console_event('MFA_BREAK_GLASS_RESET', 'UserAccount', ?, ?)";

    @Autowired
    MockMvc mvc;

    @Autowired
    MutableClock clock;

    @Autowired
    SwitchableMfaPolicy policy;

    @Autowired
    JdbcClient jdbc;

    /** deploy/mfa-break-glass.sh's connection: never the pool, which cannot execute the console functions (D168). */
    final JdbcClient console = OwnerDatabase.shared().jdbc();

    MfaFixtures.Enrolled admin;
    String reason;

    @BeforeEach
    void lastAdministrator() throws Exception {
        admin = new MfaFixtures(mvc, clock).enrolledUser(Set.of(IdentityFixtures.ADMINISTRATOR));
        reason = "phone lost, ticket " + UUID.randomUUID();
    }

    @AfterEach
    void resetMode() {
        policy.reset();
    }

    @Test
    void theScriptsTransactionRevokesTheFactorAndWritesOneSystemAuditRow() throws Exception {
        UUID returned = inOneTransaction(() -> {
            UUID account = console.sql(RESET).param("  " + admin.user().username().toUpperCase() + " ").param(reason)
                    .query(UUID.class).single();
            console.sql(SESSIONS).param(account).query(Integer.class).single();
            console.sql(AUDIT).param(account.toString()).param(reason).query(UUID.class).single();
            return account;
        });

        assertThat(returned).isEqualTo(admin.user().id());
        Map<String, Object> factor = jdbc.sql("SELECT status, revoked_by_account_id, revoke_reason, revoked_at "
                + "FROM identity.mfa_factor WHERE account_id = ? AND status <> 'PENDING'").param(returned).query()
                .singleRow();
        assertThat(factor).containsEntry("status", "REVOKED").containsEntry("revoke_reason", reason);
        assertThat(factor.get("revoked_by_account_id")).isNull();
        assertThat(factor.get("revoked_at")).isNotNull();
        Map<String, Object> row = jdbc.sql("SELECT actor_id, actor_kind, action, entity_type, entity_id, "
                + "details ->> 'via' AS via FROM audit.audit_event WHERE reason = ?").param(reason).query()
                .singleRow();
        assertThat(row.get("actor_id")).isNull();
        assertThat(row).containsEntry("actor_kind", "SYSTEM").containsEntry("action", "MFA_BREAK_GLASS_RESET")
                .containsEntry("entity_type", "UserAccount").containsEntry("entity_id", returned.toString())
                .containsEntry("via", "console");

        policy.set(MfaMode.ALL_BACKOFFICE);
        mvc.perform(IdentityFixtures.loginRequest(admin.user().username(), IdentityFixtures.STRONG_PASSWORD))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mfa.state").value("ENROLMENT_REQUIRED"));
    }

    @Test
    void theAccountsLiveSessionsEndAndItsOpenEnrolmentStateGoes() throws Exception {
        jakarta.servlet.http.Cookie live = new IdentityFixtures(mvc).login(admin.user().username(),
                IdentityFixtures.STRONG_PASSWORD);
        jakarta.servlet.http.Cookie other = new IdentityFixtures(mvc).login(admin.user().username(),
                IdentityFixtures.STRONG_PASSWORD);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/session")
                .cookie(live)).andExpect(status().isOk());
        jdbc.sql("""
                        INSERT INTO identity.mfa_factor (id, account_id, kind, status, secret_ciphertext, secret_nonce,
                            key_id, created_at, pending_expires_at, version)
                        VALUES (uuidv7(), ?, 'TOTP', 'PENDING', '\\x00', '\\x000000000000000000000000', 'k', now(),
                            now() + interval '10 minutes', 0)""").param(admin.user().id()).update();
        jdbc.sql("""
                        INSERT INTO identity.mfa_enrolment_code (id, account_id, code_hash, key_id, expires_at,
                            issued_by_account_id, reason, created_at)
                        VALUES (uuidv7(), ?, repeat('a', 64), 'k', now() + interval '1 day', ?, 'r', now())""")
                .param(admin.user().id()).param(admin.user().id()).update();

        int ended = inOneTransaction(() -> {
            UUID account = console.sql(RESET).param(admin.user().username()).param(reason).query(UUID.class).single();
            int count = console.sql(SESSIONS).param(account).query(Integer.class).single();
            console.sql(AUDIT).param(account.toString()).param(reason).query(UUID.class).single();
            return count;
        });

        assertThat(ended).isGreaterThanOrEqualTo(2);
        for (jakarta.servlet.http.Cookie session : new jakarta.servlet.http.Cookie[] {live, other}) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .get("/api/v1/auth/session").cookie(session)).andExpect(status().isUnauthorized());
        }
        assertThat(jdbc.sql("SELECT count(*) FROM identity.mfa_factor WHERE account_id = ? AND status = 'PENDING'")
                .param(admin.user().id()).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM identity.mfa_enrolment_code WHERE account_id = ? "
                + "AND used_at IS NULL").param(admin.user().id()).query(Long.class).single()).isZero();
    }

    @Test
    void theConsoleFunctionsAreNotExecutableByPublic() {
        for (String function : new String[] {"identity.mfa_break_glass_reset(text,text)",
                "shared.end_account_sessions(uuid)", "audit.record_console_event(text,text,text,text)"}) {
            assertThat(jdbc.sql("""
                            SELECT count(*) FROM pg_proc p, aclexplode(coalesce(p.proacl,
                                acldefault('f', p.proowner))) a
                            WHERE p.oid = ?::regprocedure AND a.grantee = 0 AND a.privilege_type = 'EXECUTE'""")
                    .param(function).query(Long.class).single()).as("PUBLIC EXECUTE on %s", function).isZero();
            assertThat(console.sql("SELECT has_function_privilege(current_user, ?::regprocedure, 'EXECUTE')")
                    .param(function).query(Boolean.class).single()).as("the script's role on %s", function).isTrue();
            assertThat(jdbc.sql("SELECT has_function_privilege(?, ?::regprocedure, 'EXECUTE')")
                    .param(TestcontainersConfiguration.APP_ROLE).param(function).query(Boolean.class).single())
                    .as("the application role on %s", function).isFalse();
        }
    }

    @Test
    void anUnknownUsernameRollsBothBack() {
        assertThatThrownBy(() -> inOneTransaction(() -> {
            console.sql(AUDIT).param(UUID.randomUUID().toString()).param(reason).query(UUID.class).single();
            return console.sql(RESET).param("nobody-" + UUID.randomUUID()).param(reason).query(UUID.class).single();
        })).isInstanceOf(DataAccessException.class).hasMessageContaining("unknown username");

        assertThat(auditRows()).isZero();
    }

    @Test
    void aFailingAuditRowRollsTheRevocationBack() {
        assertThatThrownBy(() -> inOneTransaction(() -> {
            UUID account = console.sql(RESET).param(admin.user().username()).param(reason).query(UUID.class).single();
            return console.sql("SELECT audit.record_console_event('MFA_BREAK_GLASS_RESET', 'UserAccount', ' ', ?)")
                    .param(reason).query(UUID.class).single();
        })).isInstanceOf(DataAccessException.class);

        assertThat(jdbc.sql("SELECT count(*) FROM identity.mfa_factor WHERE account_id = ? AND status = 'ACTIVE'")
                .param(admin.user().id()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void anAccountWithoutAnActiveFactorOrABlankReasonIsRefused() {
        IdentityFixtures.User plain;
        try {
            plain = new IdentityFixtures(mvc).user(Set.of(IdentityFixtures.WAITER), null);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        assertThatThrownBy(() -> console.sql(RESET).param(plain.username()).param(reason).query(UUID.class).single())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("no active second factor");
        assertThatThrownBy(() -> console.sql(RESET).param(admin.user().username()).param("  ").query(UUID.class)
                .single()).isInstanceOf(DataAccessException.class).hasMessageContaining("reason");
        assertThat(jdbc.sql("SELECT count(*) FROM identity.mfa_factor WHERE account_id = ? AND status = 'ACTIVE'")
                .param(admin.user().id()).query(Long.class).single()).isEqualTo(1);
    }

    private long auditRows() {
        return jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE reason = ?").param(reason)
                .query(Long.class).single();
    }

    private <T> T inOneTransaction(java.util.function.Supplier<T> statements) {
        return OwnerDatabase.shared().call(owner -> statements.get());
    }
}
