package com.xavier.backend.settlement.model.dto;

import com.xavier.backend.shared.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * A refund (D77): of {@code paymentId}, to its method and target ({@code ORDER}, {@code PARTITION} with
 * {@code partitionId}, or {@code CREDIT_ACCOUNT} with {@code customerId}), in the refunder's {@code sessionId}.
 * {@code externalReference} is the terminal's or provider's reference of a non-cash refund.
 */
public record RefundView(UUID id, UUID paymentId, UUID storeId, String targetType, UUID orderId, UUID partitionId,
                         UUID customerId, String methodCode, Money amount, String reason, String externalReference,
                         UUID sessionId, UUID operatorEmployeeId, UUID approverEmployeeId, Instant confirmedAt) {
}
