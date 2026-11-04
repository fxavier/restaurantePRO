package com.xavier.backend.organization.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;

/** The company currency is fixed once the first store exists (single currency, spec §8.4). 422. */
public class CurrencyLockedException extends BusinessRuleException {

    public CurrencyLockedException() {
        super("The company currency cannot change after the first store exists.");
    }
}
