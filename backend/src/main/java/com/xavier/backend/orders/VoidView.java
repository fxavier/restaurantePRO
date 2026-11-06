package com.xavier.backend.orders;

import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A void recorded through {@link OrdersApi#voidLine}: the fact, its {@code gross} ({@link LineAmounts#of} of the
 * voided quantity at the line's snapshot price and rate), and the order's bill after it (its new version and due).
 */
public record VoidView(UUID voidId, UUID orderId, UUID lineId, BigDecimal quantity, Money gross, String reason,
                       boolean wasted, UUID operatorEmployeeId, UUID approverEmployeeId, Instant voidedAt,
                       OrderBillingView order) {
}
