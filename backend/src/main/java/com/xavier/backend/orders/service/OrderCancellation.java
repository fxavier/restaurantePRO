package com.xavier.backend.orders.service;

import com.xavier.backend.dining.DiningApi;
import com.xavier.backend.dining.model.dto.CloseReason;
import com.xavier.backend.orders.model.Order;
import com.xavier.backend.orders.repository.OrderRepository;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * OPEN → CANCELLED in the caller's transaction (r2-decisions §4 orders): only an order that bills nothing and has no
 * payment under way; a DINE_IN order frees its table through dining ({@code ORDER_CANCELLED}). Used by the cancel
 * command and by a merge that empties its source.
 */
@Component
public class OrderCancellation {

    private final OrderRepository orders;
    private final OrderViews views;
    private final OrderChanges changes;
    private final DiningApi dining;
    private final Clock clock;

    OrderCancellation(OrderRepository orders, OrderViews views, OrderChanges changes, DiningApi dining, Clock clock) {
        this.orders = orders;
        this.views = views;
        this.changes = changes;
        this.dining = dining;
        this.clock = clock;
    }

    /** @return whether the order bills nothing any more (no confirmed line with net quantity {@code > 0}) */
    public boolean billsNothing(Order order) {
        return views.billing(order).lines().isEmpty();
    }

    public void cancel(Order order, UUID employeeId, String reason) {
        order.cancel(billsNothing(order), employeeId, clock.instant());
        orders.saveAndFlush(order);
        if (order.getTableId() != null) {
            dining.closeOccupation(order.getId(), CloseReason.ORDER_CANCELLED);
        }
        changes.recordWithReason("ORDER_CANCELLED", OrderChanges.ENTITY, order.getId(), order, null, reason,
                Map.of("orderNumber", order.getOrderNumber()));
    }
}
