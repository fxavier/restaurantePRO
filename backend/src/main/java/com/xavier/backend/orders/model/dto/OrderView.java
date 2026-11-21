package com.xavier.backend.orders.model.dto;

import com.xavier.backend.orders.BillingLock;
import com.xavier.backend.orders.OrderLifecycle;
import com.xavier.backend.orders.OrderMode;
import com.xavier.backend.shared.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * An order with its lines (drafts first priced as a preview) and rounds. {@code due} is the sum of the confirmed
 * lines' gross for their net quantities (D59). {@code catalogVersion} is the published catalog the previews come
 * from (send it back to confirm), {@code null} when the order has no draft. R4: {@code promotionVersion} is the
 * published promotion release the previews come from (send it back to confirm, {@code null} = none, D104),
 * {@code customerId} the order's customer (D130).
 *
 * <p>FE-F27: {@code draftGross} is Σ gross of the drafts as confirming them all now would price them, the exact value
 * confirmation compares with {@code expectedGross} (send it back unchanged); provisional, never billed. {@code null}
 * when there is no draft or a draft has no price (its item left the catalog). {@code closedAt} is set once the order
 * is CLOSED or CANCELLED.
 */
@NullMarked
public record OrderView(UUID id, UUID storeId, UUID terminalId, long orderNumber, OrderMode mode,
                        @Nullable UUID tableId, @Nullable String tableCode, @Nullable Integer guests,
                        OrderLifecycle lifecycle, BillingLock billingLock, LocalDate businessDay, Instant openedAt,
                        UUID openedByEmployeeId, long version, @Nullable Long catalogVersion, List<LineView> lines,
                        List<BatchView> batches, Money due, @Nullable Long promotionVersion,
                        @Nullable UUID customerId, @Nullable Money draftGross, @Nullable Instant closedAt) {
}
