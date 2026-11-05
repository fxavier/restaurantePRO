package com.xavier.backend.reporting.service;

import com.xavier.backend.cash.CashApi;
import com.xavier.backend.reporting.repository.RebuildRuns;
import com.xavier.backend.shared.events.EventPublicationStats;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * How current and how final a report is (D86). {@code pendingFacts} is the count of publications to reporting's
 * listeners not completed yet (installation-wide: the registry does not say which store an event is about). A range
 * is final when nothing is pending and every one of its business dates has a FINANCIALLY_CLOSED cash day: a date
 * without a cash day is not final, as its facts may still be in doubt. A rebuild overlapping the range that is
 * RUNNING, or whose latest run FAILED, leaves the range not final until a rebuild of it completes (its rows were
 * deleted and are not all back).
 */
@Component
class Freshness {

    static final String REPORTING_LISTENERS = "com.xavier.backend.reporting.";
    static final String FINANCIALLY_CLOSED = "FINANCIALLY_CLOSED";

    private final EventPublicationStats publications;
    private final CashApi cash;
    private final RebuildRuns rebuilds;

    Freshness(EventPublicationStats publications, CashApi cash, RebuildRuns rebuilds) {
        this.publications = publications;
        this.cash = cash;
        this.rebuilds = rebuilds;
    }

    long pendingFacts() {
        return publications.pendingFor(REPORTING_LISTENERS);
    }

    boolean isFinal(UUID storeId, LocalDate from, LocalDate to, long pendingFacts) {
        return pendingFacts == 0 && rebuilds.rangeIntact(storeId, from, to) && from.datesUntil(to.plusDays(1)).allMatch(day -> cash.findDay(storeId, day)
                .map(found -> FINANCIALLY_CLOSED.equals(found.status())).orElse(false));
    }
}
