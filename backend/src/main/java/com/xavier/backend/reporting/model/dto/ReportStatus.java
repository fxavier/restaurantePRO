package com.xavier.backend.reporting.model.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * How current the store's projections are (D86): the facts not projected yet, the newest projection of the store
 * ({@code null} before the first) and its last rebuild ({@code null} when it never had one).
 */
public record ReportStatus(UUID storeId, long pendingFacts, Instant lastProjectedAt, RebuildView lastRebuild) {
}
