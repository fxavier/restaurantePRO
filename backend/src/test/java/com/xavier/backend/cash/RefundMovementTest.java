package com.xavier.backend.cash;

import static com.xavier.backend.cash.CashFixtures.pos;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.access.Permission;
import com.xavier.backend.cash.CashFixtures.Pos;
import com.xavier.backend.cash.exception.CashRule;
import com.xavier.backend.cash.exception.CashRuleViolationException;
import com.xavier.backend.shared.Money;
import com.xavier.backend.shared.exception.ConflictException;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A23 (movement half): a refund settlement confirmed is one {@code REFUND} OUT movement of the payment's method in
 * the refunder's OPEN session, once per {@code refundId} (spec §8.2 "Referência de origem única").
 */
@CashModuleTest
class RefundMovementTest {

    static final Currency MZN = Currency.getInstance("MZN");

    @Autowired
    CashApi cash;

    @Autowired
    CashFixtures fixtures;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    Pos operator;
    UUID session;

    @BeforeEach
    void openSession() throws Exception {
        UUID store = fixtures.newStore();
        operator = pos(store);
        session = fixtures.openSession(operator, fixtures.newRegister(store), "100.00");
        SecurityContextHolder.getContext().setAuthentication(
                CashFixtures.authenticationOf(operator.actor(Permission.CASH_OPERATE)));
    }

    @AfterEach
    void clearActor() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aRefundIsOneOutMovementOfItsMethodThatLowersWhatTheSessionShouldHold() {
        UUID refund = UUID.randomUUID();

        MovementView recorded = inTx(() -> cash.recordRefund(refund("CARD", "15.00", refund)));

        assertThat(recorded.type()).isEqualTo("REFUND");
        assertThat(recorded.methodCode()).isEqualTo("CARD");
        assertThat(recorded.sessionId()).isEqualTo(session);
        assertThat(jdbc.sql("""
                        SELECT direction || ' ' || source_type FROM cash.cash_movement
                        WHERE source_id = ? AND type = 'REFUND'""").param(refund).query(String.class).single())
                .isEqualTo("OUT REFUND");
        assertThat(jdbc.sql("""
                        SELECT sum(CASE WHEN direction = 'IN' THEN amount ELSE -amount END) FROM cash.cash_movement
                        WHERE session_id = ? AND method_code = 'CARD'""").param(session).query(String.class).single())
                .isEqualTo("-15.0000");
    }

    @Test
    void recordingTheSameRefundTwiceKeepsOneRow() {
        UUID refund = UUID.randomUUID();

        MovementView first = inTx(() -> cash.recordRefund(refund("CASH", "5.00", refund)));
        MovementView second = inTx(() -> cash.recordRefund(refund("CASH", "5.00", refund)));

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(fixtures.count("SELECT count(*) FROM cash.cash_movement WHERE source_id = ?", refund)).isOne();
    }

    @Test
    void aRepeatedRefundIdWithAnotherAmountOrMethodIsAConflict() {
        UUID refund = UUID.randomUUID();
        inTx(() -> cash.recordRefund(refund("CASH", "5.00", refund)));

        for (RefundCommand other : List.of(refund("CASH", "6.00", refund), refund("CARD", "5.00", refund))) {
            assertThatThrownBy(() -> inTx(() -> cash.recordRefund(other)))
                    .isInstanceOfSatisfying(ConflictException.class,
                            e -> assertThat(e.code()).isEqualTo("REFUND_MISMATCH"));
        }
        assertThat(fixtures.count("SELECT count(*) FROM cash.cash_movement WHERE source_id = ?", refund)).isOne();
    }

    @Test
    void aRefundIntoAClosedSessionIsRefused() {
        inTx(() -> {
            cash.closeSession(session, cash.lockSession(session).version(),
                    List.of(new MethodCount("CASH", Money.of("100.00", MZN))), operator.employeeId());
            return null;
        });

        assertThatThrownBy(() -> inTx(() -> cash.recordRefund(refund("CASH", "5.00", UUID.randomUUID()))))
                .isInstanceOfSatisfying(CashRuleViolationException.class,
                        e -> assertThat(e.rule()).isEqualTo(CashRule.SESSION_NOT_OPEN));
    }

    @Test
    void recordingARefundJoinsTheCallersTransaction() {
        assertThatThrownBy(() -> cash.recordRefund(refund("CASH", "1.00", UUID.randomUUID())))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void theDatabaseKeepsARefundOutboundAndSourcedByItsRefund() {
        assertThatThrownBy(() -> insertRefund("IN", "REFUND", UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("cash_movement_direction_ck");
        assertThatThrownBy(() -> insertRefund("OUT", "PAYMENT", UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("cash_movement_source_ck");
        UUID refund = UUID.randomUUID();
        insertRefund("OUT", "REFUND", refund);
        assertThatThrownBy(() -> insertRefund("OUT", "REFUND", refund))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("cash_movement_source_uk");
    }

    private void insertRefund(String direction, String sourceType, UUID sourceId) {
        jdbc.sql("""
                        INSERT INTO cash.cash_movement (id, store_id, cash_day_id, session_id, register_id, type,
                            direction, method_code, amount, currency, source_type, source_id, actor_employee_id,
                            occurred_at)
                        SELECT uuidv7(), s.store_id, s.cash_day_id, s.id, s.register_id, 'REFUND', :direction,
                            'CARD', 1, 'MZN', :sourceType, :sourceId, s.operator_employee_id, now()
                        FROM cash.cash_session s WHERE s.id = :session""")
                .param("direction", direction).param("sourceType", sourceType).param("sourceId", sourceId)
                .param("session", session).update();
    }

    private RefundCommand refund(String method, String amount, UUID refundId) {
        return new RefundCommand(session, method, Money.of(amount, MZN), refundId, operator.employeeId());
    }

    private <T> T inTx(Supplier<T> action) {
        return tx.execute(status -> action.get());
    }
}
