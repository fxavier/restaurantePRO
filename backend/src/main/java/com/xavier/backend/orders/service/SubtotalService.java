package com.xavier.backend.orders.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.dining.DiningApi;
import com.xavier.backend.orders.OrderBillingView;
import com.xavier.backend.orders.model.Order;
import com.xavier.backend.orders.model.dto.SubtotalView;
import com.xavier.backend.orders.model.event.SubtotalLine;
import com.xavier.backend.orders.model.event.SubtotalRequested;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The subtotal printout (spec §8.1: a non-fiscal bill, not a document): publishes {@link SubtotalRequested} for
 * production to print on the session's terminal printer and marks the table ({@code DiningApi.markSubtotalPrinted},
 * §8.4). It changes nothing of the order, so the order's version stays.
 */
@Service
public class SubtotalService {

    private final OrderLookup lookup;
    private final OrderViews views;
    private final DiningApi dining;
    private final AccessPolicy accessPolicy;
    private final ApplicationEventPublisher events;
    private final OrderChanges changes;

    SubtotalService(OrderLookup lookup, OrderViews views, DiningApi dining, AccessPolicy accessPolicy,
                    ApplicationEventPublisher events, OrderChanges changes) {
        this.lookup = lookup;
        this.views = views;
        this.dining = dining;
        this.accessPolicy = accessPolicy;
        this.events = events;
        this.changes = changes;
    }

    /** @throws com.xavier.backend.orders.exception.OrderConflictException 409 {@code ORDER_NOT_OPEN} */
    @Transactional
    public SubtotalView print(UUID orderId) {
        PosContext pos = accessPolicy.requirePos(Permission.ORDER_EDIT);
        Order order = lookup.inSessionStore(pos, orderId);
        order.requireChangeable();
        OrderBillingView bill = views.billing(order);
        List<SubtotalLine> lines = bill.lines().stream()
                .map(line -> new SubtotalLine(line.description(), line.netQty(), line.unitPrice(), line.gross()))
                .toList();
        events.publishEvent(new SubtotalRequested(orderId, order.getStoreId(), pos.terminalId(),
                order.getOrderNumber(), order.getTableCode(), lines, bill.due()));
        if (order.getTableId() != null) {
            dining.markSubtotalPrinted(orderId);
        }
        changes.record("ORDER_SUBTOTAL_REQUESTED", order, Map.of("due", bill.due().amount().toPlainString(),
                "lines", lines.size()));
        return new SubtotalView(orderId, order.getOrderNumber(), order.getTableCode(), lines, bill.due());
    }
}
