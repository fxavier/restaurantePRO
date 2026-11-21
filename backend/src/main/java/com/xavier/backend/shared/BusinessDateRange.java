package com.xavier.backend.shared;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * An inclusive range of business dates for a list filter, where an absent bound means unbounded. The bounds are
 * resolved here, never in the query: PostgreSQL cannot type a {@code null} date parameter in {@code :from is null}.
 */
public record BusinessDateRange(LocalDate from, LocalDate to) {

    static final LocalDate EARLIEST = LocalDate.EPOCH;
    static final LocalDate LATEST = LocalDate.of(9999, 12, 31);

    public static BusinessDateRange of(@Nullable LocalDate from, @Nullable LocalDate to) {
        return new BusinessDateRange(from == null ? EARLIEST : from, to == null ? LATEST : to);
    }
}
