package com.xavier.backend.orders.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.orders.BillingLock;
import com.xavier.backend.orders.OrderLifecycle;
import com.xavier.backend.orders.OrderMode;
import com.xavier.backend.orders.exception.OrderConflictException;
import com.xavier.backend.orders.exception.OrderRuleException;
import com.xavier.backend.shared.exception.CodedException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** r2-decisions §4 orders: the Order lifecycle and its billing lock (D49), every state × command. */
class OrderTest {

    static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");
    static final UUID EMPLOYEE = UUID.randomUUID();

    static Order dineIn() {
        return Order.dineIn(UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID(), null, 2,
                LocalDate.of(2026, 11, 4), Currency.getInstance("MZN"), EMPLOYEE, NOW);
    }

    static Order counter() {
        return Order.walkIn(OrderMode.COUNTER, UUID.randomUUID(), UUID.randomUUID(), 2, null,
                LocalDate.of(2026, 11, 4), Currency.getInstance("MZN"), EMPLOYEE, NOW);
    }

    @Test
    void aNewOrderIsOpenUnlockedAndStartsAtRoundOne() {
        Order order = dineIn();

        assertThat(order.getLifecycle()).isEqualTo(OrderLifecycle.OPEN);
        assertThat(order.getBillingLock()).isEqualTo(BillingLock.NONE);
        assertThat(order.getMode()).isEqualTo(OrderMode.DINE_IN);
        assertThat(order.confirmBatch()).isEqualTo(1);
        assertThat(order.confirmBatch()).isEqualTo(2);
    }

    @Test
    void aWalkInOrderHasNoTableAndCannotBeDineIn() {
        assertThat(counter().getTableId()).isNull();
        assertThatThrownBy(() -> Order.walkIn(OrderMode.DINE_IN, UUID.randomUUID(), UUID.randomUUID(), 1, null,
                LocalDate.now(), Currency.getInstance("MZN"), EMPLOYEE, NOW))
                .isInstanceOfSatisfying(OrderRuleException.class,
                        e -> assertThat(e.code()).isEqualTo("DINE_IN_REQUIRES_TABLE"));
    }

    @Test
    void thePaymentLockStillAllowsRoundsAndIsReleasedBackToNone() {
        Order order = counter();

        assertThat(order.lockForPayment()).isTrue();
        assertThat(order.lockForPayment()).isFalse();
        order.confirmBatch();
        assertThat(order.releasePaymentLock()).isTrue();
        assertThat(order.getBillingLock()).isEqualTo(BillingLock.NONE);
        assertThat(order.releasePaymentLock()).isFalse();
    }

    @Test
    void theInvoiceLockFreezesEveryChangeAndIsNeverReleased() {
        Order order = counter();
        order.lockForPayment();

        assertThat(order.freezeForInvoice()).isTrue();
        assertThat(order.freezeForInvoice()).isFalse();
        assertThat(order.lockForPayment()).isFalse();
        assertThat(order.releasePaymentLock()).isFalse();
        assertThat(order.getBillingLock()).isEqualTo(BillingLock.INVOICE);
        assertCode(order::confirmBatch, "ORDER_FROZEN");
        assertCode(order::requireChangeable, "ORDER_FROZEN");
    }

    @Test
    void closingEndsTheOrderOnceAndAClosedOrderRefusesEveryCommand() {
        Order order = counter();
        order.freezeForInvoice();

        order.close(EMPLOYEE, NOW);

        assertThat(order.getLifecycle()).isEqualTo(OrderLifecycle.CLOSED);
        assertThat(order.getClosedAt()).isEqualTo(NOW);
        assertCode(() -> order.close(EMPLOYEE, NOW), "ORDER_NOT_OPEN");
        assertCode(order::confirmBatch, "ORDER_NOT_OPEN");
        assertCode(order::requireChangeable, "ORDER_NOT_OPEN");
        assertCode(order::lockForPayment, "ORDER_NOT_OPEN");
        assertCode(order::freezeForInvoice, "ORDER_NOT_OPEN");
    }

    static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(CodedException.class, e -> {
            assertThat(e.code()).isEqualTo(code);
            assertThat(e).isInstanceOf(code.equals("ORDER_NOT_OPEN")
                    ? OrderConflictException.class : OrderRuleException.class);
        });
    }
}
