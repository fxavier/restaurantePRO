package com.xavier.backend.reporting.model.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A rebuild run (D84): RUNNING, then COMPLETED or FAILED (with {@code error}); {@code projected} counts the facts
 * pulled so far (its progress).
 */
public record RebuildView(UUID id, UUID storeId, LocalDate from, LocalDate to, String status, long projected,
                          Instant startedAt, Instant finishedAt, String error) {
}
