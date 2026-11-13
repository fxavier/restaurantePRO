package com.xavier.backend.identity.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D143 break-glass: {@code deploy/mfa-break-glass.sh} runs {@code identity.mfa_break_glass_reset} and
 * {@code audit.record_console_event} in one transaction. Here the same two statements run in one JDBC transaction:
 * the factor is revoked and exactly one system audit row is written, or neither happens.
 */
@MfaTest
class BreakGlassFunctionTest {

    static final String RESET = "SELECT identity.mfa_break_glass_reset(?, ?)";
    static final String AUDIT = "SELECT audit.record_console_event('MFA_BREAK_GLASS_RESET', 'UserAccount', ?, ?)";

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
            UUID account = jdbc.sql(RESET).param("  " + admin.user().username().toUpperCase() + " ").param(reason)
                    .query(UUID.class).single();
            jdbc.sql(AUDIT).param(account.toString()).param(reason).query(UUID.class).single();
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
    void anUnknownUsernameRollsBothBack() {
        assertThatThrownBy(() -> inOneTransaction(() -> {
            jdbc.sql(AUDIT).param(UUID.randomUUID().toString()).param(reason).query(UUID.class).single();
            return jdbc.sql(RESET).param("nobody-" + UUID.randomUUID()).param(reason).query(UUID.class).single();
        })).isInstanceOf(DataAccessException.class).hasMessageContaining("unknown username");

        assertThat(auditRows()).isZero();
    }

    @Test
    void aFailingAuditRowRollsTheRevocationBack() {
        assertThatThrownBy(() -> inOneTransaction(() -> {
            UUID account = jdbc.sql(RESET).param(admin.user().username()).param(reason).query(UUID.class).single();
            return jdbc.sql("SELECT audit.record_console_event('MFA_BREAK_GLASS_RESET', 'UserAccount', ' ', ?)")
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

        assertThatThrownBy(() -> jdbc.sql(RESET).param(plain.username()).param(reason).query(UUID.class).single())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("no active second factor");
        assertThatThrownBy(() -> jdbc.sql(RESET).param(admin.user().username()).param("  ").query(UUID.class)
                .single()).isInstanceOf(DataAccessException.class).hasMessageContaining("reason");
        assertThat(jdbc.sql("SELECT count(*) FROM identity.mfa_factor WHERE account_id = ? AND status = 'ACTIVE'")
                .param(admin.user().id()).query(Long.class).single()).isEqualTo(1);
    }

    private long auditRows() {
        return jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE reason = ?").param(reason)
                .query(Long.class).single();
    }

    private <T> T inOneTransaction(java.util.function.Supplier<T> statements) {
        return new TransactionTemplate(transactions).execute(tx -> statements.get());
    }
}
