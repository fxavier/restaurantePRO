package com.xavier.backend.inventory.model.dto;

import java.util.UUID;

/** {@code balances} replayed, of which {@code corrected} differed from the ledger and were rewritten. */
public record RebuildView(UUID warehouseId, int balances, int corrected) {
}
