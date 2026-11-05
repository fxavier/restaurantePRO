package com.xavier.backend.settlement;

import com.xavier.backend.shared.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A posted credit sale as reporting reads it (D74, D84; posted from S29, which may refine this record before it is
 * published): the amount put on the customer's account, its target, document and order business day.
 */
public record CreditChargeFact(UUID chargeId, UUID storeId, LocalDate businessDate, Instant at, Money amount,
                               UUID orderId, UUID partitionId, UUID customerId, UUID documentId,
                               UUID postedByEmployeeId, UUID terminalId) {
}
