package com.xavier.backend.orders.model.dto;

import com.xavier.backend.orders.BillingLock;
import com.xavier.backend.orders.OrderLifecycle;
import com.xavier.backend.orders.OrderMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** An order in a list; read the order for its lines and due. */
public record OrderSummary(UUID id, long orderNumber, OrderMode mode, UUID tableId, String tableCode,
                           Integer guests, OrderLifecycle lifecycle, BillingLock billingLock, LocalDate businessDay,
                           Instant openedAt, UUID openedByEmployeeId, long version) {
}
