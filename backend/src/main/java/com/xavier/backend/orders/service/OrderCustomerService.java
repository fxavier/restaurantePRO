package com.xavier.backend.orders.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.customers.CustomersApi;
import com.xavier.backend.orders.AdjustmentKind;
import com.xavier.backend.orders.model.Order;
import com.xavier.backend.orders.model.dto.CustomerRequest;
import com.xavier.backend.orders.model.dto.OrderView;
import com.xavier.backend.orders.repository.OrderLinePriceAdjustmentRepository;
import com.xavier.backend.orders.repository.OrderRepository;
import com.xavier.backend.shared.persistence.OptimisticLocking;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Names an order's customer (D130): checked through {@code CustomersApi} (404 unknown, 422 {@code CUSTOMER_INACTIVE}),
 * refused once a reward was redeemed for the current customer (422 {@code LOYALTY_REDEEMED}, D112). It is how
 * loyalty finds the member; it does not set a document's buyer (P17, D74).
 */
@Service
public class OrderCustomerService {

    private final OrderRepository orders;
    private final OrderLinePriceAdjustmentRepository adjustments;
    private final OrderLookup lookup;
    private final CustomersApi customers;
    private final AccessPolicy accessPolicy;
    private final OrderChanges changes;
    private final OrderViews views;

    OrderCustomerService(OrderRepository orders, OrderLinePriceAdjustmentRepository adjustments, OrderLookup lookup,
                         CustomersApi customers, AccessPolicy accessPolicy, OrderChanges changes, OrderViews views) {
        this.orders = orders;
        this.adjustments = adjustments;
        this.lookup = lookup;
        this.customers = customers;
        this.accessPolicy = accessPolicy;
        this.changes = changes;
        this.views = views;
    }

    @Transactional
    public OrderView assign(UUID orderId, CustomerRequest request) {
        PosContext pos = accessPolicy.requirePos(Permission.ORDER_EDIT);
        Order order = lookup.inSessionStore(pos, orderId);
        OptimisticLocking.requireVersion(Order.class, orderId, order.getVersion(), request.orderVersion());
        customers.snapshot(request.customerId());
        order.assignCustomer(request.customerId(), adjustments.existsByOrderIdAndKind(orderId, AdjustmentKind.REWARD));
        orders.saveAndFlush(order);
        changes.record("ORDER_CUSTOMER_SET", order, Map.of("customerId", request.customerId().toString()));
        return views.of(order);
    }
}
