package com.xavier.backend.orders.model;

import static com.xavier.backend.orders.model.OrderTest.EMPLOYEE;
import static com.xavier.backend.orders.model.OrderTest.NOW;
import static com.xavier.backend.orders.model.OrderTest.assertCode;
import static com.xavier.backend.orders.model.OrderTest.counter;
import static com.xavier.backend.orders.model.OrderTest.dineIn;
import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.orders.OrderLifecycle;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * r2-decisions §4 orders, D49/P13 (S14): what voids, outgoing transfers, cancel and a table move accept in each
 * lifecycle × billing lock.
 */
class OrderAdjustmentTest {

    @Test
    void anOpenUnlockedOrderAcceptsVoidsAndOutgoingTransfers() {
        dineIn().requireBillReducible();
    }

    @Test
    void thePaymentLockRefusesVoidsAndOutgoingTransfersButStillTakesIncomingLines() {
        Order order = counter();
        order.lockForPayment();

        assertCode(order::requireBillReducible, "ORDER_IN_SETTLEMENT");
        order.requireChangeable();
    }

    @Test
    void theInvoiceLockRefusesEveryAdjustment() {
        Order order = dineIn();
        order.freezeForInvoice();

        assertCode(order::requireBillReducible, "ORDER_FROZEN");
        assertCode(() -> order.moveTo(UUID.randomUUID()), "ORDER_FROZEN");
        assertCode(() -> order.cancel(true, EMPLOYEE, NOW), "ORDER_FROZEN");
    }

    @Test
    void aClosedOrderRefusesEveryAdjustment() {
        Order order = dineIn();
        order.close(EMPLOYEE, NOW);

        assertCode(order::requireBillReducible, "ORDER_NOT_OPEN");
        assertCode(() -> order.moveTo(UUID.randomUUID()), "ORDER_NOT_OPEN");
        assertCode(() -> order.cancel(true, EMPLOYEE, NOW), "ORDER_NOT_OPEN");
    }

    @Test
    void anOrderWithNothingBillableAndNoLockIsCancelledOnce() {
        Order order = dineIn();

        order.cancel(true, EMPLOYEE, NOW);

        assertThat(order.getLifecycle()).isEqualTo(OrderLifecycle.CANCELLED);
        assertThat(order.getClosedAt()).isEqualTo(NOW);
        assertCode(() -> order.cancel(true, EMPLOYEE, NOW), "ORDER_NOT_OPEN");
    }

    @Test
    void cancelNeedsNothingBillableAndNoPaymentLock() {
        assertCode(() -> dineIn().cancel(false, EMPLOYEE, NOW), "ORDER_NOT_EMPTY");
        Order paying = dineIn();
        paying.lockForPayment();
        assertCode(() -> paying.cancel(true, EMPLOYEE, NOW), "ORDER_IN_SETTLEMENT");
    }

    @Test
    void aTableMoveSeatsTheOrderAtTheNewTableAndTakesItsCodeFromDining() {
        Order order = dineIn();
        order.seatedAt(order.getTableId(), "M1");
        UUID target = UUID.randomUUID();

        order.moveTo(target);
        order.seatedAt(target, "M7");

        assertThat(order.getTableId()).isEqualTo(target);
        assertThat(order.getTableCode()).isEqualTo("M7");
    }

    @Test
    void aTableMoveForgetsTheOldCodeUntilDiningGivesTheNewOne() {
        Order order = dineIn();
        order.seatedAt(order.getTableId(), "M1");

        order.moveTo(UUID.randomUUID());

        assertThat(order.getTableCode()).isNull();
    }

    @Test
    void anOrderWithoutATableCannotMove() {
        assertCode(() -> counter().moveTo(UUID.randomUUID()), "ORDER_HAS_NO_TABLE");
    }
}
