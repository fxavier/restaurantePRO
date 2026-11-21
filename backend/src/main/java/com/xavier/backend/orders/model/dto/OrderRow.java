package com.xavier.backend.orders.model.dto;

import com.xavier.backend.orders.OrderLifecycle;
import com.xavier.backend.orders.OrderMode;
import com.xavier.backend.shared.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * An order in the BackOffice search. {@code due} is Σ gross of its confirmed lines for their net quantities (D59), as
 * {@link OrderView#due()}; {@code closedAt} is set once the order is CLOSED or CANCELLED.
 */
@NullMarked
public record OrderRow(UUID id, UUID storeId, long orderNumber, OrderMode mode, @Nullable String tableCode,
                       @Nullable Integer guests, @Nullable UUID customerId, OrderLifecycle lifecycle,
                       LocalDate businessDay, Instant openedAt, @Nullable Instant closedAt, Money due) {
}
