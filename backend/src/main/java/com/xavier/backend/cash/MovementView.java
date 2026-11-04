package com.xavier.backend.cash;

import com.xavier.backend.shared.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * A recorded cash movement. {@code type} is {@code OPENING_FLOAT}, {@code PAYMENT_RECEIPT}, {@code CASH_IN},
 * {@code CASH_OUT} or {@code EXPENSE}; {@code paymentId} is set for a receipt only.
 */
public record MovementView(UUID id, UUID sessionId, UUID cashDayId, UUID storeId, String type, String methodCode,
                           Money amount, UUID paymentId, Instant occurredAt) {
}
