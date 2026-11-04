package com.xavier.backend.orders.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.orders.exception.OrderNotFoundException;
import com.xavier.backend.orders.model.Order;
import com.xavier.backend.orders.repository.OrderRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Loads an order addressed by id in D42 (b) order: its store only (404), then the session store check (403). */
@Component
public class OrderLookup {

    private final OrderRepository orders;
    private final AccessPolicy accessPolicy;

    OrderLookup(OrderRepository orders, AccessPolicy accessPolicy) {
        this.orders = orders;
        this.accessPolicy = accessPolicy;
    }

    public Order inSessionStore(PosContext pos, UUID orderId) {
        UUID storeId = orders.findStoreIdById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(OrderChanges.ENTITY, orderId));
        accessPolicy.requireSessionStore(pos, storeId, OrderChanges.ENTITY, orderId);
        return load(orderId);
    }

    public Order load(UUID orderId) {
        return orders.findById(orderId).orElseThrow(() -> new OrderNotFoundException(OrderChanges.ENTITY, orderId));
    }
}
