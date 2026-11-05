package com.xavier.backend.cash;

import static com.xavier.backend.cash.CashFixtures.pos;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.access.Permission;
import com.xavier.backend.cash.CashFixtures.Pos;
import com.xavier.backend.shared.Money;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * P22, D79 (4): a manager closes a session its operator abandoned, counting the drawer blind and giving a reason.
 * The session keeps who closed it and that the close was FORCED, and the close is audited. (Settlement checks the
 * payments in doubt first and owns the endpoint, S30.)
 */
@CashModuleTest
class ForcedCloseCashTest {

    static final Currency MZN = Currency.getInstance("MZN");

    @Autowired
    CashApi cash;

    @Autowired
    CashFixtures fixtures;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    UUID store;
    Pos operator;
    Pos manager;
    UUID session;

    @BeforeEach
    void anAbandonedSession() throws Exception {
        store = fixtures.newStore();
        operator = pos(store);
        manager = pos(store);
        session = fixtures.openSession(operator, fixtures.newRegister(store), "40.00");
        SecurityContextHolder.getContext().setAuthentication(
                CashFixtures.authenticationOf(manager.actor(Permission.CASH_SESSION_FORCE_CLOSE)));
    }

    @AfterEach
    void clearActor() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aManagerForceClosesAnAbandonedSessionWithABlindCountAndAReason() {
        forceClose(version(), "shift ended without a close");

        assertThat(jdbc.sql("""
                        SELECT status || ' ' || close_kind || ' ' || closed_by_employee_id || ' ' || close_reason
                        FROM cash.cash_session WHERE id = ?""").param(session).query(String.class).single())
                .isEqualTo("CLOSED FORCED " + manager.employeeId() + " shift ended without a close");
        assertThat(fixtures.count("""
                SELECT count(*) FROM cash.cash_session_count WHERE session_id = ? AND amount = 38.00""", session))
                .isOne();
        assertThat(fixtures.count("""
                SELECT count(*) FROM audit.audit_event
                WHERE action = 'CASH_SESSION_FORCE_CLOSED' AND entity_id = ? AND actor_id = ?
                  AND reason = 'shift ended without a close'
                  AND details @> '{"operatorEmployeeId": "%s", "counts": [{"methodCode": "CASH", "amount": "38.00"}]}'::jsonb"""
                .formatted(operator.employeeId()), session.toString(), manager.employeeId())).isOne();
    }

    @Test
    void anOperatorsOwnCloseIsRecordedAsAnOperatorClose() {
        SecurityContextHolder.getContext().setAuthentication(
                CashFixtures.authenticationOf(operator.actor(Permission.CASH_OPERATE)));
        tx.executeWithoutResult(status -> cash.closeSession(session, version(), List.of(), operator.employeeId()));

        assertThat(jdbc.sql("SELECT close_kind FROM cash.cash_session WHERE id = ?").param(session)
                .query(String.class).single()).isEqualTo("OPERATOR");
    }

    @Test
    void aForcedCloseNeedsAReasonTheCurrentActorAndTheCurrentVersion() {
        long version = version();

        assertThatThrownBy(() -> forceClose(version, " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> cash.closeSessionForced(session, version,
                List.of(), operator.employeeId(), "not me"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> forceClose(version + 1, "stale"))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThatThrownBy(() -> cash.closeSessionForced(session, version, List.of(), manager.employeeId(), "x"))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void theDatabaseRequiresAReasonForAForcedClose() {
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO cash.cash_session (id, store_id, register_id, cash_day_id, operator_employee_id,
                            terminal_id, status, opening_float, currency, opened_at, closed_at, closed_by_employee_id,
                            close_kind, version)
                        SELECT uuidv7(), store_id, register_id, cash_day_id, gen_random_uuid(), terminal_id, 'CLOSED',
                            0, currency, now(), now(), gen_random_uuid(), 'FORCED', 0
                        FROM cash.cash_session WHERE id = ?""").param(session).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("cash_session_close_kind_ck");
    }

    private void forceClose(long version, String reason) {
        tx.executeWithoutResult(status -> cash.closeSessionForced(session, version,
                List.of(new MethodCount("CASH", Money.of("38.00", MZN))), manager.employeeId(), reason));
    }

    private long version() {
        return tx.execute(status -> cash.lockSession(session).version());
    }
}
