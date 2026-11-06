package com.xavier.backend.settlement.reconciliation.model.dto;

import com.xavier.backend.shared.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * One reconciliation entry (D78). {@code lineNo} is the statement's data line ({@code null} for MISSING_PROVIDER);
 * {@code internalAmount} is the payment net of its refunds; {@code difference = provider − internal};
 * {@code resolution} is {@code null} while the entry is open.
 */
public record EntryView(UUID id, UUID batchId, Integer lineNo, String kind, UUID paymentId, String providerReference,
                        String authorizationCode, String lineRef, Instant occurredAt, Money internalAmount,
                        Money providerAmount, Money fee, Money difference, ResolutionView resolution) {
}
