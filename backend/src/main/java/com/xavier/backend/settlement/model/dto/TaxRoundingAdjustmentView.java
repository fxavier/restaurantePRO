package com.xavier.backend.settlement.model.dto;

import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One tax rounding adjustment of an order (D96 rule 5): {@code kind} is {@code ABSORBED} (the residual line of
 * {@code targetLineId}'s share in {@code targetPartitionId}, {@code null} for its pool, took {@code amount}) or
 * {@code PENDING_CORRECTION} ({@code documentId} needs a correction; {@code resolution} is set once it is
 * corrected).
 */
public record TaxRoundingAdjustmentView(UUID id, UUID orderId, UUID storeId, LocalDate businessDate,
                                        UUID sourceLineId, BigDecimal ratePercent, Money amount, String kind,
                                        UUID documentId, UUID targetLineId, UUID targetPartitionId, String actorKind,
                                        UUID actorId, Instant createdAt, TaxCorrectionResolutionView resolution) {
}
