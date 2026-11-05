package com.xavier.backend.settlement.model.dto;

import com.xavier.backend.shared.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * A credit sale as the POS sees it (D74). {@code targetType} is {@code ORDER} (the order's residual) or
 * {@code PARTITION} (with {@code partitionId}); {@code status} is {@code RESERVED} (the limit is held while the
 * document is issued), {@code POSTED} (on the customer's ledger, with {@code ledgerEntryId}) or {@code RELEASED}
 * (cancelled before its document was issued, with {@code releaseReason}). {@code documentId} is the target's sale
 * document, on account. RESERVED and POSTED charges count in the financial state; neither is money received.
 */
public record CreditChargeView(UUID id, UUID orderId, String targetType, UUID partitionId, UUID customerId,
                               Money amount, String status, UUID documentId, UUID ledgerEntryId, Instant createdAt,
                               Instant postedAt, Instant releasedAt, String releaseReason, long version) {
}
