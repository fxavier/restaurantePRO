package com.xavier.backend.inventory.service;

import com.xavier.backend.inventory.exception.InventoryRuleException;
import java.time.LocalDate;

/** A period of business days given by a request. */
final class Periods {

    private Periods() {
    }

    /** @throws InventoryRuleException INVALID_PERIOD when it starts after it ends */
    static void require(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw InventoryRuleException.invalidPeriod(from, to);
        }
    }
}
