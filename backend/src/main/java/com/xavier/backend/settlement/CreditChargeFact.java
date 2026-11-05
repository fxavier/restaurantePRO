package com.xavier.backend.settlement;

import com.xavier.backend.shared.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A POSTED credit sale as reporting reads it (D74, D84): the amount put on the customer's account (never money
 * received), its target ({@code partitionId} null for the order's residual), its on-account document and the order's
 * business day; {@code at} is when it was posted, {@code postedByEmployeeId} who posted it ({@code null} when the
 * recovery sweeper did), and {@code terminalId} where it was sold.
 */
public record CreditChargeFact(UUID chargeId, UUID storeId, LocalDate businessDate, Instant at, Money amount,
                               UUID orderId, UUID partitionId, UUID customerId, UUID documentId,
                               UUID postedByEmployeeId, UUID terminalId) {
}
