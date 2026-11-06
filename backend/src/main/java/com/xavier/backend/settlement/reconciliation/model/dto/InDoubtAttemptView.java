package com.xavier.backend.settlement.reconciliation.model.dto;

import com.xavier.backend.shared.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * A payment attempt still PENDING or UNKNOWN (D81): its target, amount, age in seconds, and what the in-doubt
 * resolver asked the provider so far ({@code pollCount}, {@code answeredCount}, {@code lastPollAnswer}).
 */
public record InDoubtAttemptView(UUID attemptId, UUID orderId, UUID partitionId, UUID creditAccountId,
                                 String methodCode, String methodKind, String status, Money amount,
                                 Instant createdAt, long ageSeconds, int pollCount, int answeredCount,
                                 String lastPollAnswer, Instant lastPolledAt, Instant nextPollAt) {
}
