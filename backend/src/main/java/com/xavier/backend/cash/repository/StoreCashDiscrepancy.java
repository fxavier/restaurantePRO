package com.xavier.backend.cash.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * D150 CASH_DISCREPANCY: a store's closings with a difference over the threshold, and when the first of them was
 * submitted.
 */
public record StoreCashDiscrepancy(UUID storeId, Long closings, Instant firstSubmittedAt) {
}
