package com.xavier.backend.orders;

import com.xavier.backend.shared.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What settlement needs to bill an order (D36): its version (for the close), state, lock and business day, the
 * billable lines (lines with nothing left to bill are omitted) and {@code due} = Σ line gross (never re-rounded).
 * {@code mode} is how the order is served (P15); {@code tableId} and {@code tableCode} are those the order sits at now
 * ({@code null} for COUNTER and TAKEAWAY).
 */
public record OrderBillingView(UUID orderId, UUID storeId, long version, OrderLifecycle lifecycle,
                               BillingLock billingLock, LocalDate businessDay, List<BillingLine> lines, Money due,
                               OrderMode mode, UUID tableId, String tableCode) {
}
