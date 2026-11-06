package com.xavier.backend.reporting.service;

import com.xavier.backend.reporting.exception.ReportRangeException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/** A store and a range of business dates, both inclusive, of at most {@link #MAX_DAYS} days (D85). */
record ReportRange(UUID storeId, LocalDate from, LocalDate to) {

    static final long MAX_DAYS = 366;

    ReportRange {
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.isAfter(to)) {
            throw ReportRangeException.reversed(from, to);
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw ReportRangeException.tooLarge(MAX_DAYS);
        }
    }
}
