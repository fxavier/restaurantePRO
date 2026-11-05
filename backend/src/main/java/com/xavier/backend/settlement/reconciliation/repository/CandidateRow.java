package com.xavier.backend.settlement.reconciliation.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A payment with Σ of its refunds and its attempt's references, as the statement matcher reads it. */
public record CandidateRow(UUID paymentId, BigDecimal amount, BigDecimal refunded, String currency,
                           Instant confirmedAt, String providerReference, String providerTransactionRef,
                           String authorizationCode) {
}
