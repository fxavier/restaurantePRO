package com.xavier.backend.cash;

import static com.xavier.backend.cash.CashFixtures.pos;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.access.ActorKind;
import com.xavier.backend.access.Permission;
import com.xavier.backend.cash.CashFixtures.Pos;
import com.xavier.backend.cash.exception.CashRule;
import com.xavier.backend.cash.exception.CashRuleViolationException;
import com.xavier.backend.cash.exception.IllegalCashStateException;
import com.xavier.backend.shared.Money;
import com.xavier.backend.shared.exception.NotFoundException;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What settlement asks cash (PRD S11 {@code CashApi}), inside its own transaction as settlement will (D36). The
 * current actor is the POS operator, as in a settlement HTTP request.
 */
@CashModuleTest
class CashApiTest {

    static final Currency MZN = Currency.getInstance("MZN");

    @Autowired
    CashApi cash;

    @Autowired
    CashFixtures fixtures;

    @Autowired
    TransactionTemplate tx;

    UUID store;
    Pos operator;
    UUID session;

    @BeforeEach
    void openSession() throws Exception {
        store = fixtures.newStore();
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
    void recordingAReceiptTwiceWithOnePaymentIdKeepsOneMovement() {
        UUID payment = UUID.randomUUID();

        MovementView first = inTx(() -> cash.recordReceipt(receipt("CASH", "25.00", payment)));
        MovementView second = inTx(() -> cash.recordReceipt(receipt("CASH", "25.00", payment)));

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(first.type()).isEqualTo("PAYMENT_RECEIPT");
        assertThat(first.paymentId()).isEqualTo(payment);
        assertThat(fixtures.count("SELECT count(*) FROM cash.cash_movement WHERE source_id = ?", payment))
                .isEqualTo(1);
    }

    @Test
    void aNonCashReceiptIsKeptUnderItsMethodAndNeverAddsToTheCashInTheDrawer() {
        inTx(() -> cash.recordReceipt(receipt("CARD", "40.00", UUID.randomUUID())));

        assertThat(fixtures.count("""
                SELECT count(*) FROM cash.cash_movement WHERE session_id = ? AND method_code = 'CASH'""", session))
                .as("only the opening float is cash").isEqualTo(1);
        assertThat(fixtures.count("""
                SELECT count(*) FROM cash.cash_movement WHERE session_id = ? AND method_code = 'CARD'""", session))
                .isEqualTo(1);
    }

    @Test
    void writesJoinTheCallersTransactionAndRefuseToRunWithoutOne() {
        assertThatThrownBy(() -> cash.recordReceipt(receipt("CASH", "1.00", UUID.randomUUID())))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> cash.lockSession(session)).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void theOpenSessionOfTheCollectingOperatorRoutesThePayment() {
        assertThat(cash.openSessionOf(store, operator.employeeId())).hasValueSatisfying(open -> {
            assertThat(open.id()).isEqualTo(session);
            assertThat(open.storeId()).isEqualTo(store);
        });
        assertThat(cash.openSessionOf(store, UUID.randomUUID())).isEmpty();

        close(session);

        assertThat(cash.openSessionOf(store, operator.employeeId())).isEmpty();
    }

    @Test
    void closingASessionRecordsTheBlindCountAndEndsReceipts() {
        UUID payment = UUID.randomUUID();
        inTx(() -> cash.recordReceipt(receipt("CASH", "10.00", payment)));

        close(session);

        assertThat(fixtures.count("SELECT count(*) FROM cash.cash_session_count WHERE session_id = ?", session))
                .isEqualTo(2);
        assertThatThrownBy(() -> inTx(() -> cash.recordReceipt(receipt("CASH", "1.00", UUID.randomUUID()))))
                .isInstanceOfSatisfying(CashRuleViolationException.class,
                        e -> assertThat(e.rule()).isEqualTo(CashRule.SESSION_NOT_OPEN));
        assertThat(inTx(() -> cash.recordReceipt(receipt("CASH", "10.00", payment))).paymentId())
                .as("a retried receipt of a recorded payment still answers").isEqualTo(payment);
    }

    @Test
    void aStaleSessionVersionOrASecondCloseIsAConflict() {
        long version = inTx(() -> cash.lockSession(session)).version();

        assertThatThrownBy(() -> inTx(() -> {
            cash.closeSession(session, version + 1, List.of(), operator.employeeId());
            return null;
        })).isInstanceOf(OptimisticLockingFailureException.class);

        close(session);
        assertThatThrownBy(() -> close(session)).isInstanceOf(IllegalCashStateException.class);
    }

    @Test
    void aDayClosesOperationallyOnlyWhenEverySessionIsClosedAndThenAcceptsNoSession() throws Exception {
        SessionView view = inTx(() -> cash.lockSession(session));
        DayView day = inTx(() -> cash.findDay(view.cashDayId()).orElseThrow());
        assertThat(cash.openSessionIds(day.id())).containsExactly(session);

        assertThatThrownBy(() -> closeDay(day.id(), day.version()))
                .isInstanceOfSatisfying(CashRuleViolationException.class,
                        e -> assertThat(e.rule()).isEqualTo(CashRule.OPEN_SESSIONS));

        close(session);
        closeDay(day.id(), day.version());

        DayView closed = inTx(() -> cash.lockDay(store, day.businessDate()));
        assertThat(closed.status()).isEqualTo("OPERATIONALLY_CLOSED");
        assertThatThrownBy(() -> closeDay(day.id(), closed.version())).isInstanceOf(IllegalCashStateException.class);
        assertThatThrownBy(() -> fixtures.openSession(pos(store), fixtures.newRegister(store), "1.00"))
                .hasMessageContaining("422");
    }

    @Test
    void aStaleDayVersionIsAConflictAndAnUnknownDayIsNotFound() {
        SessionView view = inTx(() -> cash.lockSession(session));
        close(session);
        DayView day = cash.findDay(view.cashDayId()).orElseThrow();

        assertThatThrownBy(() -> closeDay(day.id(), day.version() + 1))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThatThrownBy(() -> inTx(() -> cash.lockDay(store, LocalDate.of(1999, 1, 1))))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> inTx(() -> cash.lockSession(UUID.randomUUID())))
                .isInstanceOf(NotFoundException.class);
    }

    private void close(UUID id) {
        inTx(() -> {
            long version = cash.lockSession(id).version();
            cash.closeSession(id, version, List.of(new MethodCount("CASH", Money.of("110.00", MZN)),
                    new MethodCount("CARD", Money.of("0.00", MZN))), operator.employeeId());
            return null;
        });
    }

    private void closeDay(UUID dayId, long version) {
        inTx(() -> {
            cash.closeDayOperationally(dayId, version, operator.employeeId(), ActorKind.EMPLOYEE);
            return null;
        });
    }

    private ReceiptCommand receipt(String method, String amount, UUID paymentId) {
        return new ReceiptCommand(session, method, Money.of(amount, MZN), paymentId, operator.employeeId());
    }

    private <T> T inTx(Supplier<T> action) {
        return tx.execute(status -> action.get());
    }
}
