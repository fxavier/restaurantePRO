package com.xavier.backend.orders.model.event;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/**
 * An order was closed (S53): its billing lines are final. Reporting pulls {@code OrdersApi.offerFacts(orderId)} for
 * the cost of its offers. {@code businessDay} is the order's.
 */
@NamedInterface("event")
public record OrderClosed(UUID orderId, UUID storeId, LocalDate businessDay) {
}
