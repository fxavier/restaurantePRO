package com.xavier.backend.reporting.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One report over the business dates {@code [from, to]} of a store (D85): its rows by {@code groupBy}, the
 * {@code totals} over the whole range, and its freshness and finality (D86). {@code asOf} is the newest projection
 * the report read ({@code null} when it read none); {@code pendingFacts} counts the facts reporting has been told of
 * but not projected yet; {@code final} is true once every business date of the range is FINANCIALLY_CLOSED and
 * nothing is pending.
 */
public record ReportView<T>(UUID storeId, LocalDate from, LocalDate to, String groupBy, List<T> rows, T totals,
                            Instant asOf, long pendingFacts, @JsonProperty("final") boolean isFinal) {
}
