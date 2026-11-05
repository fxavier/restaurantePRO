package com.xavier.backend.settlement.model.dto;

import com.xavier.backend.shared.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * A payment attempt as the POS sees it: the source of truth for its outcome (§7.4). {@code methodKind} is
 * {@code CASH}, {@code EXTERNAL_TERMINAL} or {@code INTEGRATED}; {@code status} is {@code CREATED}, {@code PENDING},
 * {@code SUCCEEDED}, {@code FAILED}, {@code CANCELLED} or {@code UNKNOWN}. {@code tendered} and {@code change} are set
 * for cash only. {@code outcomeDetail} is the provider's failure or doubt reason; {@code resolutionReason} the
 * operator's cancel or reclassification reason. {@code targetType} is {@code ORDER} or {@code PARTITION} (with its
 * {@code partitionId}, D69).
 */
public record AttemptView(UUID id, UUID orderId, UUID storeId, UUID sessionId, String methodCode, String methodKind,
                          String status, Money amount, Money tendered, Money change, String authorizationCode,
                          String outcomeDetail, String resolutionReason, Instant createdAt, Instant updatedAt,
                          String targetType, UUID partitionId) {
}
