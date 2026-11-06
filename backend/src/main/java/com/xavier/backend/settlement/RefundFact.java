package com.xavier.backend.settlement;

import com.xavier.backend.shared.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A refund as reporting reads it (D77, D84; recorded from S30, which may refine this record before it is
 * published): the refunded payment and its target, the method and amount, the refunder's cash day and terminal, and
 * the approver.
 */
public record RefundFact(UUID refundId, UUID paymentId, UUID storeId, LocalDate businessDate, Instant at,
                         String methodCode, Money amount, UUID orderId, UUID partitionId, UUID customerId,
                         UUID refundedByEmployeeId, UUID approverEmployeeId, UUID terminalId) {
}
