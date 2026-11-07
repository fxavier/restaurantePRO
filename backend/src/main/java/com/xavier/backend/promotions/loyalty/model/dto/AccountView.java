package com.xavier.backend.promotions.loyalty.model.dto;

import java.time.Instant;
import java.util.UUID;

/** A loyalty account: status, suspension reason, enrollment and the balance (Σ of the ledger, D110). */
public record AccountView(UUID customerId, String status, String suspendedReason, Instant enrolledAt, long points,
                          long version) {
}
