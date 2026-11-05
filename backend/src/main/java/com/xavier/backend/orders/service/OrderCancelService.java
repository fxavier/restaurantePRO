package com.xavier.backend.orders.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.orders.model.Order;
import com.xavier.backend.orders.model.dto.CancelOrderRequest;
import com.xavier.backend.orders.model.dto.OrderView;
import com.xavier.backend.shared.persistence.OptimisticLocking;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The POS cancel of an order that bills nothing (D55: what the POS uses instead of a close when nothing is due). */
@Service
public class OrderCancelService {

    private final OrderLookup lookup;
    private final OrderCancellation cancellation;
    private final AccessPolicy accessPolicy;
    private final OrderViews views;

    OrderCancelService(OrderLookup lookup, OrderCancellation cancellation, AccessPolicy accessPolicy,
                       OrderViews views) {
        this.lookup = lookup;
        this.cancellation = cancellation;
        this.accessPolicy = accessPolicy;
        this.views = views;
    }

    /** @throws com.xavier.backend.orders.exception.OrderRuleException 422 {@code ORDER_NOT_EMPTY},
     *         {@code ORDER_IN_SETTLEMENT}, {@code ORDER_FROZEN} */
    @Transactional
    public OrderView cancel(UUID orderId, CancelOrderRequest request) {
        PosContext pos = accessPolicy.requirePos(Permission.ORDER_CANCEL);
        Order order = lookup.inSessionStore(pos, orderId);
        OptimisticLocking.requireVersion(Order.class, orderId, order.getVersion(), request.orderVersion());
        cancellation.cancel(order, pos.employeeId(), request.reason().strip());
        return views.of(order);
    }
}
