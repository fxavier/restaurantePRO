package com.xavier.backend.inventory.service;

import com.xavier.backend.inventory.exception.InventoryRuleException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** A period of business days given by a request. */
final class Periods {

    /** The longest report period, as reporting's ranges (D85). */
    static final long MAX_DAYS = 366;

    private Periods() {
    }

    /** @throws InventoryRuleException INVALID_PERIOD when it starts after it ends */
    static void require(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw InventoryRuleException.invalidPeriod(from, to);
        }
    }

    /**
     * A report period: both ends given, in order, at most {@value #MAX_DAYS} days.
     *
     * @throws InventoryRuleException INVALID_PERIOD or RANGE_TOO_LARGE (with {@code maxDays})
     */
    static void requireReport(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw InventoryRuleException.periodRequired();
        }
        require(from, to);
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw InventoryRuleException.rangeTooLarge(MAX_DAYS);
        }
    }
}
