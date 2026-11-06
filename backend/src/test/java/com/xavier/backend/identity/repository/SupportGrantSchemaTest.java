package com.xavier.backend.identity.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.identity.IdentityTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The database backs the support-grant invariants on its own (D83, V3011), whatever code writes the rows. */
@IdentityTest
class SupportGrantSchemaTest {

    static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @Autowired
    JdbcClient jdbc;

    UUID account;
    UUID admin;

    @BeforeEach
    void accounts() {
        account = account(true);
        admin = account(false);
    }

    @Test
    void aGrantLongerThan24HoursIsRefusedByTheDatabase() {
        assertThatThrownBy(() -> insert(account, Duration.ofHours(24).plusSeconds(1), null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("support_grant_max_24h_ck");
    }

    @Test
    void aGrantOfExactly24HoursIsAccepted() {
        insert(account, Duration.ofHours(24), null, null);

        assertThat(count(account)).isEqualTo(1);
    }

    @Test
    void anAccountHoldsAtMostOneActiveGrant() {
        insert(account, Duration.ofHours(1), null, null);

        assertThatThrownBy(() -> insert(account, Duration.ofHours(1), null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("support_grant_one_active_uk");
    }

    @Test
    void endedGrantsDoNotBlockANewOne() {
        insert(account, Duration.ofHours(1), NOW, null);
        insert(account, Duration.ofHours(1), null, NOW);

        insert(account, Duration.ofHours(1), null, null);

        assertThat(count(account)).isEqualTo(3);
    }

    @Test
    void aGrantIsNeverBothRevokedAndExpired() {
        assertThatThrownBy(() -> insert(account, Duration.ofHours(1), NOW, NOW))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("support_grant_one_ending_ck");
    }

    @Test
    void aSupportAccountIsNeverLinkedToAnEmployee() {
        UUID employee = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO identity.employee (id, code, name, pin_hash, failed_pin_attempts, locked, active, version,
                                                    created_at, updated_at)
                        VALUES (:id, :code, 'Ana', '{bcrypt}$2a$x', 0, false, true, 0, now(), now())""")
                .param("id", employee).param("code", "E" + employee.toString().substring(0, 8)).update();

        assertThatThrownBy(() -> jdbc.sql("UPDATE identity.user_account SET employee_id = :e WHERE id = :id")
                .param("e", employee).param("id", account).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("user_account_support_unlinked_ck");
    }

    private UUID account(boolean support) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO identity.user_account (id, username, password_hash, must_change_password, active,
                                                           support, version, created_at, updated_at)
                        VALUES (:id, :username, '{bcrypt}$2a$x', false, true, :support, 0, now(), now())""")
                .param("id", id).param("username", "schema-" + id).param("support", support).update();
        return id;
    }

    private void insert(UUID accountId, Duration duration, Instant revokedAt, Instant expiredAt) {
        jdbc.sql("""
                        INSERT INTO identity.support_grant (id, account_id, valid_from, valid_until, reason,
                                                            granted_by_account_id, revoked_at, revoked_by_account_id,
                                                            expired_at, created_at, version)
                        VALUES (:id, :account, :from, :until, 'ticket', :admin, :revoked, :revokedBy, :expired,
                                :from, 0)""")
                .param("id", UUID.randomUUID()).param("account", accountId)
                .param("from", Timestamp.from(NOW)).param("until", Timestamp.from(NOW.plus(duration)))
                .param("admin", admin).param("revoked", revokedAt == null ? null : Timestamp.from(revokedAt))
                .param("revokedBy", revokedAt == null ? null : admin)
                .param("expired", expiredAt == null ? null : Timestamp.from(expiredAt))
                .update();
    }

    private long count(UUID accountId) {
        return jdbc.sql("SELECT count(*) FROM identity.support_grant WHERE account_id = :a").param("a", accountId)
                .query(Long.class).single();
    }
}
