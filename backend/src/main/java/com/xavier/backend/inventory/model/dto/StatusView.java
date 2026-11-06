package com.xavier.backend.inventory.model.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Inventory health of a store (D116, D118, D122): {@code state} is ACTIVE or INVENTORY_NOT_ACTIVE;
 * {@code pendingFacts} the consumption publications not processed yet (installation-wide, D86);
 * {@code openExceptions} unresolved consumption exceptions; {@code negativeBalances} balances below zero;
 * {@code driftingBalances} balance rows that differ from their movements (rebuild them).
 */
public record StatusView(UUID storeId, String state, Instant activeFrom, long pendingFacts, long openExceptions,
                         long negativeBalances, long driftingBalances) {
}
