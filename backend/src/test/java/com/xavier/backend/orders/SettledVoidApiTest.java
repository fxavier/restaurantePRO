package com.xavier.backend.orders;

import static com.xavier.backend.orders.OrdersFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.orders.model.event.OrderLinesVoided;
import com.xavier.backend.shared.Money;
import com.xavier.backend.shared.exception.CodedException;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link OrdersApi#voidLine} as settlement's coordinated void calls it (D71, P32): under {@code order_settlement}, with
 * the headroom already checked, so the PAYMENT lock is bypassed; INVOICE still refuses. The fact, its event and its
 * audit are the R2 void's.
 */
@OrdersModuleTest
class SettledVoidApiTest {

    static final Currency MZN = Currency.getInstance("MZN");

    @Autowired
    OrdersApi ordersApi;

    @Autowired
    OrdersFixtures fixtures;

    @Autowired
    OrderEventProbe events;

    @Autowired
    PlatformTransactionManager transactionManager;

    final UUID operator = UUID.randomUUID();
    final UUID approver = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();
    Shop shop;
    UUID order;
    UUID line;

    @BeforeEach
    void aRoundOfThreeUnderAPaymentLock() throws Exception {
        shop = fixtures.shop();
        order = id(fixtures.openedOrder(shop, operator, terminal));
        line = fixtures.confirmedLine(shop, order, "3", 0, operator, terminal);
        inPos(() -> run(() -> ordersApi.lockForPayment(order)));
    }

    @Test
    void underThePaymentLockTheVoidIsRecordedLikeTheR2Void() throws Exception {
        VoidView voided = inPos(() -> ordersApi.voidLine(command(2, "1"), approver));

        assertThat(voided.orderId()).isEqualTo(order);
        assertThat(voided.lineId()).isEqualTo(line);
        assertThat(voided.quantity()).isEqualByComparingTo("1");
        assertThat(voided.gross()).isEqualTo(Money.of("12.50", MZN));
        assertThat(voided.reason()).isEqualTo("wrong dish");
        assertThat(voided.wasted()).isTrue();
        assertThat(voided.operatorEmployeeId()).isEqualTo(operator);
        assertThat(voided.approverEmployeeId()).isEqualTo(approver);
        assertThat(voided.order().version()).isEqualTo(3);
        assertThat(voided.order().billingLock()).isEqualTo(BillingLock.PAYMENT);
        assertThat(voided.order().due()).isEqualTo(Money.of("25.00", MZN));
        assertThat(fixtures.count("""
                SELECT count(*) FROM audit.audit_event WHERE action = 'ORDER_LINE_VOIDED' AND entity_id = ?
                    AND actor_id = ? AND approver_id = ? AND reason = 'wrong dish'""",
                voided.voidId().toString(), operator, approver)).isOne();
    }

    @Test
    void theVoidTellsProductionOnTheSessionsTerminal() throws Exception {
        VoidView voided = inPos(() -> ordersApi.voidLine(command(2, "2"), approver));

        assertThat(events.voidsOf(order, 1)).singleElement().satisfies((OrderLinesVoided event) -> {
            assertThat(event.voidId()).isEqualTo(voided.voidId());
            assertThat(event.line().lineId()).isEqualTo(line);
            assertThat(event.quantity()).isEqualByComparingTo("2");
            assertThat(event.terminalId()).isEqualTo(terminal);
            assertThat(event.storeId()).isEqualTo(shop.store());
        });
    }

    @Test
    void anOrderFrozenForItsInvoiceIsRefused() {
        inPos(() -> run(() -> ordersApi.freezeForInvoice(order)));

        assertThatThrownBy(() -> inPos(() -> ordersApi.voidLine(command(3, "1"), approver)))
                .isInstanceOfSatisfying(CodedException.class, e -> assertThat(e.code()).isEqualTo("ORDER_FROZEN"));
        assertThat(voids()).isZero();
    }

    @Test
    void aClosedOrderIsNotOpenAndNothingIsVoided() {
        inPos(() -> run(() -> ordersApi.close(order, 2, operator)));

        assertThatThrownBy(() -> inPos(() -> ordersApi.voidLine(command(3, "1"), approver)))
                .isInstanceOfSatisfying(CodedException.class, e -> assertThat(e.code()).isEqualTo("ORDER_NOT_OPEN"));
        assertThat(voids()).isZero();
    }

    @Test
    void aStaleVersionLosesAndNothingIsVoided() {
        assertThatThrownBy(() -> inPos(() -> ordersApi.voidLine(command(1, "1"), approver)))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(voids()).isZero();
    }

    @Test
    void neverMoreThanTheNetQuantity() {
        assertThatThrownBy(() -> inPos(() -> ordersApi.voidLine(command(2, "3.5"), approver)))
                .isInstanceOfSatisfying(CodedException.class,
                        e -> assertThat(e.code()).isEqualTo("QUANTITY_EXCEEDS_NET"));
        assertThat(voids()).isZero();
    }

    @Test
    void theApproverIsNeverTheOperator() {
        assertThatThrownBy(() -> inPos(() -> ordersApi.voidLine(command(2, "1"), operator)))
                .isInstanceOfSatisfying(CodedException.class, e -> assertThat(e.code()).isEqualTo("SELF_APPROVAL"));
        assertThat(voids()).isZero();
    }

    @Test
    void itNeedsTheCallersTransaction() {
        assertThatThrownBy(() -> OrdersActors.runAsPos(shop.store(), operator, terminal,
                () -> ordersApi.voidLine(command(2, "1"), approver)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    SettledVoidCommand command(long expectedVersion, String quantity) {
        return new SettledVoidCommand(order, expectedVersion, line, new BigDecimal(quantity), "wrong dish", true,
                operator);
    }

    long voids() {
        return fixtures.count("SELECT count(*) FROM orders.order_line_void WHERE order_id = ?", order);
    }

    <T> T inPos(Supplier<T> action) {
        return OrdersActors.runAsPos(shop.store(), operator, terminal,
                () -> new TransactionTemplate(transactionManager).execute(status -> action.get()));
    }

    static Void run(Runnable action) {
        action.run();
        return null;
    }
}
