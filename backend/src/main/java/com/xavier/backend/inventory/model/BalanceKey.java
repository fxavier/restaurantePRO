package com.xavier.backend.inventory.model;

import java.util.Comparator;
import java.util.UUID;

/**
 * A balance row's key. {@link #LOCK_ORDER} is PostgreSQL's {@code (warehouse_id, stock_item_id)} order (a uuid
 * compares as its unsigned bytes, which is the order of its lower-case text), the one order every posting locks
 * balance rows in (D113, D132), so two postings never deadlock.
 */
public record BalanceKey(UUID warehouseId, UUID stockItemId) {

    public static final Comparator<BalanceKey> LOCK_ORDER = Comparator
            .comparing((BalanceKey key) -> key.warehouseId().toString())
            .thenComparing(key -> key.stockItemId().toString());
}
