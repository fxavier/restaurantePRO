package com.xavier.backend.orders.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.dining.DiningApi;
import com.xavier.backend.orders.exception.OrderRuleException;
import com.xavier.backend.orders.exception.OrderNotFoundException;
import com.xavier.backend.orders.model.Order;
import com.xavier.backend.orders.repository.OrderRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
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
        requireSessionStore(pos, orderId);
        return load(orderId);
    }

    /** D42 (b) without loading the order: 404 when it does not exist, 403 when it is another store's. */
    public void requireSessionStore(PosContext pos, UUID orderId) {
        UUID storeId = orders.findStoreIdById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(OrderChanges.ENTITY, orderId));
        accessPolicy.requireSessionStore(pos, storeId, OrderChanges.ENTITY, orderId);
    }

    /**
     * The order at the version the client showed, with that version moved on first ({@link
     * OrderRepository#incrementVersion}): from here to commit this transaction is the order's only writer, so the
     * net quantities it reads stay true. Callers that claim two orders claim them in ascending id order.
     *
     * @throws ObjectOptimisticLockingFailureException 409 when the order is at another version
     */
    public Order claim(UUID orderId, long expectedVersion) {
        return claim(Map.of(orderId, expectedVersion)).get(orderId);
    }

    /**
     * {@link #claim(UUID, long)} for several orders, always in ascending id order: two transactions claiming the same
     * orders queue on the first one instead of each holding one and waiting for the other (no deadlock).
     */
    public Map<UUID, Order> claim(Map<UUID, Long> expectedVersions) {
        expectedVersions.keySet().stream().sorted().forEach(orderId -> {
            if (orders.incrementVersion(orderId, expectedVersions.get(orderId)) == 0) {
                throw new ObjectOptimisticLockingFailureException(Order.class, orderId);
            }
        });
        Map<UUID, Order> claimed = new HashMap<>();
        expectedVersions.keySet().forEach(orderId -> claimed.put(orderId, load(orderId)));
        return claimed;
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
