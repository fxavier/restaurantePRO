package com.xavier.backend.orders.model.dto;

import com.xavier.backend.orders.BillingLock;
import com.xavier.backend.orders.OrderLifecycle;
import com.xavier.backend.orders.OrderMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** An order in a list; read the order for its lines and due. */
@NullMarked
public record OrderSummary(UUID id, long orderNumber, OrderMode mode, @Nullable UUID tableId,
                           @Nullable String tableCode, @Nullable Integer guests, OrderLifecycle lifecycle,
                           BillingLock billingLock, LocalDate businessDay, Instant openedAt, UUID openedByEmployeeId,
                           long version) {
}
