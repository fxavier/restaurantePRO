package com.xavier.backend.settlement;

import com.xavier.backend.shared.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A confirmed payment as reporting reads it (D84, D85): {@code businessDate} is its cash session's day ({@code null}
 * for a payment recorded before R3), {@code at} its confirmation, {@code targetType} {@code ORDER}, {@code PARTITION}
 * or {@code CREDIT_ACCOUNT} (with {@code customerId}, S29), and {@code terminalId} the attempt's terminal.
 */
public record PaymentFact(UUID paymentId, UUID storeId, LocalDate businessDate, Instant at, String methodCode,
                          Money amount, String targetType, UUID orderId, UUID partitionId, UUID customerId,
                          UUID collectedByEmployeeId, UUID terminalId) {
}
