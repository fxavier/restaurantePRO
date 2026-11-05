package com.xavier.backend.cash.repository;

import static com.xavier.backend.cash.CashFixtures.pos;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.access.Permission;
import com.xavier.backend.cash.CashApi;
import com.xavier.backend.cash.CashFixtures;
import com.xavier.backend.cash.CashFixtures.Pos;
import com.xavier.backend.cash.CashModuleTest;
import com.xavier.backend.cash.MethodCount;
import com.xavier.backend.shared.Money;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Confirmed money facts are immutable at the database level (spec §8, A15, D61): whatever the application does,
 * PostgreSQL refuses to rewrite or remove a cash movement or a blind count, and refuses a movement that did not come
 * from its legal source.
 */
@CashModuleTest
class CashSchemaTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    CashFixtures fixtures;

    @Autowired
    CashApi cash;

    @Autowired
    TransactionTemplate tx;

    UUID session;

    @BeforeEach
    void closedSessionWithAFloatAndACount() throws Exception {
        UUID store = fixtures.newStore();
        Pos operator = pos(store);
        session = fixtures.openSession(operator, fixtures.newRegister(store), "50.00");
        SecurityContextHolder.getContext().setAuthentication(
                CashFixtures.authenticationOf(operator.actor(Permission.CASH_OPERATE)));
        tx.executeWithoutResult(status -> cash.closeSession(session, cash.lockSession(session).version(),
                List.of(new MethodCount("CASH", Money.of("50.00", Currency.getInstance("MZN")))),
                operator.employeeId()));
    }

    @AfterEach
    void clearActor() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void theOldestOpenDayOfAStoreHasAPartialIndex() {
        assertThat(jdbc.sql("""
                        SELECT indexdef FROM pg_indexes WHERE schemaname = 'cash' AND indexname = 'cash_day_store_open_ix'""")
                .query(String.class).optional())
                .hasValueSatisfying(definition -> assertThat(definition)
                        .contains("(store_id, business_date)").contains("WHERE ((status)::text = 'OPEN'::text)"));
    }

    @Test
    void aCashMovementCannotBeUpdatedOrDeleted() {
        assertThatThrownBy(() -> jdbc.sql("UPDATE cash.cash_movement SET amount = 1 WHERE session_id = ?")
                .param(session).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM cash.cash_movement WHERE session_id = ?")
                .param(session).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThat(fixtures.count("SELECT count(*) FROM cash.cash_movement WHERE session_id = ?", session))
                .isEqualTo(1);
    }

    @Test
    void aSessionCountCannotBeUpdatedOrDeleted() {
        assertThatThrownBy(() -> jdbc.sql("UPDATE cash.cash_session_count SET amount = 0 WHERE session_id = ?")
                .param(session).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM cash.cash_session_count WHERE session_id = ?")
                .param(session).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }

    @Test
    void aClosedSessionNeverReopens() {
        assertThatThrownBy(() -> jdbc.sql("UPDATE cash.cash_session SET status = 'OPEN', closed_at = NULL, "
                + "closed_by_employee_id = NULL WHERE id = ?").param(session).update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void noMovementGoesIntoAClosedSessionAndOnlyAPaymentMakesAReceipt() {
        assertThatThrownBy(() -> insertMovement("CASH_IN", "CASH", null, null, "late"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("is not open");

        UUID open = openSession();
        assertThatThrownBy(() -> insertMovement(open, "PAYMENT_RECEIPT", "CASH", null, null, null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("cash_movement_source_ck");
        assertThatThrownBy(() -> insertMovement(open, "CASH_IN", "CARD", null, null, "x"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("cash_movement_cash_method_ck");
        assertThatThrownBy(() -> insertMovement(open, "CASH_OUT", "CASH", null, null, null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("cash_movement_manual_reason_ck");
        UUID payment = UUID.randomUUID();
        insertMovement(open, "PAYMENT_RECEIPT", "CARD", "PAYMENT", payment, null);
        assertThatThrownBy(() -> insertMovement(open, "PAYMENT_RECEIPT", "CARD", "PAYMENT", payment, null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("cash_movement_source_uk");
    }

    private UUID openSession() {
        try {
            UUID store = fixtures.newStore();
            return fixtures.openSession(pos(store), fixtures.newRegister(store), "0");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void insertMovement(String type, String method, String sourceType, UUID sourceId, String reason) {
        insertMovement(session, type, method, sourceType, sourceId, reason);
    }

    private void insertMovement(UUID sessionId, String type, String method, String sourceType, UUID sourceId,
                                String reason) {
        jdbc.sql("""
                        INSERT INTO cash.cash_movement (id, store_id, cash_day_id, session_id, register_id, type,
                            direction, method_code, amount, currency, reason, source_type, source_id,
                            actor_employee_id, occurred_at)
                        SELECT uuidv7(), s.store_id, s.cash_day_id, s.id, s.register_id, :type,
                            CASE WHEN :type IN ('CASH_OUT', 'EXPENSE') THEN 'OUT' ELSE 'IN' END, :method, 1, 'MZN',
                            :reason, :sourceType, :sourceId, s.operator_employee_id, now()
                        FROM cash.cash_session s WHERE s.id = :session""")
                .param("type", type).param("method", method).param("reason", reason)
                .param("sourceType", sourceType).param("sourceId", sourceId).param("session", sessionId)
                .update();
    }
}
