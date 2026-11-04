package com.xavier.backend.orders.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.dining.DiningApi;
import com.xavier.backend.orders.exception.OrderRuleException;
import com.xavier.backend.orders.exception.OrderNotFoundException;
import com.xavier.backend.orders.model.Order;
import com.xavier.backend.orders.repository.OrderRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Loads an order addressed by id in D42 (b) order: its store only (404), then the session store check (403); and the
 * table-lock check every new line and round makes.
 */
@Component
public class OrderLookup {

    private final OrderRepository orders;
    private final AccessPolicy accessPolicy;
    private final DiningApi dining;

    OrderLookup(OrderRepository orders, AccessPolicy accessPolicy, DiningApi dining) {
        this.orders = orders;
        this.accessPolicy = accessPolicy;
        this.dining = dining;
    }

    public Order inSessionStore(PosContext pos, UUID orderId) {
        UUID storeId = orders.findStoreIdById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(OrderChanges.ENTITY, orderId));
        accessPolicy.requireSessionStore(pos, storeId, OrderChanges.ENTITY, orderId);
        return load(orderId);
    }

    /** A locked table refuses new lines and rounds (r2-decisions §4 dining); payments and close stay allowed. */
    public void requireTableUnlocked(Order order) {
        if (order.getTableId() != null && dining.tableState(order.getTableId()).locked()) {
            throw OrderRuleException.tableLocked(order.getTableId());
        }
    }

    public Order load(UUID orderId) {
        return orders.findById(orderId).orElseThrow(() -> new OrderNotFoundException(OrderChanges.ENTITY, orderId));
    }
}
