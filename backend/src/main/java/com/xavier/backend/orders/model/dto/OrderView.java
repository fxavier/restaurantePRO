package com.xavier.backend.orders.model.dto;

import com.xavier.backend.orders.BillingLock;
import com.xavier.backend.orders.OrderLifecycle;
import com.xavier.backend.orders.OrderMode;
import com.xavier.backend.shared.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * An order with its lines (drafts first priced as a preview) and rounds. {@code due} is the sum of the confirmed
 * lines' gross for their net quantities (D59). {@code catalogVersion} is the published catalog the previews come
 * from (send it back to confirm), {@code null} when the order has no draft. R4: {@code promotionVersion} is the
 * published promotion release the previews come from (send it back to confirm, {@code null} = none, D104),
 * {@code customerId} the order's customer (D130).
 */
public record OrderView(UUID id, UUID storeId, UUID terminalId, long orderNumber, OrderMode mode, UUID tableId,
                        String tableCode, Integer guests, OrderLifecycle lifecycle, BillingLock billingLock,
                        LocalDate businessDay, Instant openedAt, UUID openedByEmployeeId, long version,
                        Long catalogVersion, List<LineView> lines, List<BatchView> batches, Money due,
                        Long promotionVersion, UUID customerId) {
}
