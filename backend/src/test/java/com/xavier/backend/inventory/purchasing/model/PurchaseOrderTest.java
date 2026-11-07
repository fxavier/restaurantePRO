package com.xavier.backend.inventory.purchasing.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.inventory.purchasing.exception.PurchasingConflictException;
import com.xavier.backend.inventory.purchasing.exception.PurchasingRuleException;
import com.xavier.backend.shared.exception.CodedException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The purchase order machine (D124, §8.3): DRAFT → SUBMITTED → CLOSED; DRAFT or SUBMITTED → CANCELLED. */
class PurchaseOrderTest {

    static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");

    @Test
    void aSubmittedOrderIsFrozenReceivableAndClosedOnce() {
        PurchaseOrder order = draft();
        assertCode(order::requireReceivable, "PO_NOT_SUBMITTED");

        order.submit(NOW);

        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.SUBMITTED);
        assertThat(order.getSubmittedAt()).isEqualTo(NOW);
        order.requireReceivable();
        assertCode(() -> order.edit(List.of(line()), null, null), "PO_STATE");
        assertCode(() -> order.submit(NOW), "PO_STATE");

        order.close(NOW);

        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.CLOSED);
        assertThat(order.getClosedAt()).isEqualTo(NOW);
        assertCode(order::requireReceivable, "PO_NOT_SUBMITTED");
        assertCode(() -> order.close(NOW), "PO_STATE");
        assertCode(() -> order.cancel(false, NOW), "PO_STATE");
    }

    @Test
    void aDraftIsNeverClosedButIsCancelled() {
        PurchaseOrder order = draft();
        assertCode(() -> order.close(NOW), "PO_STATE");

        order.cancel(false, NOW);

        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.CANCELLED);
        assertThat(order.getCancelledAt()).isEqualTo(NOW);
        assertCode(() -> order.submit(NOW), "PO_STATE");
    }

    @Test
    void aSubmittedOrderWithConfirmedReceiptsIsClosedNotCancelled() {
        PurchaseOrder order = draft();
        order.submit(NOW);

        assertThatThrownBy(() -> order.cancel(true, NOW)).isInstanceOf(PurchasingRuleException.class)
                .extracting(e -> ((CodedException) e).code()).isEqualTo("PO_HAS_RECEIPTS");
        order.cancel(false, NOW);
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.CANCELLED);
    }

    private static PurchaseOrder draft() {
        return PurchaseOrder.draft(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "PO-1",
                List.of(line()), null, null, null, NOW);
    }

    private static PurchaseOrderLine line() {
        return PurchaseOrderLine.of(UUID.randomUUID(), null, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE);
    }

    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfAny(PurchasingConflictException.class,
                PurchasingRuleException.class).extracting(e -> ((CodedException) e).code()).isEqualTo(code);
    }
}
