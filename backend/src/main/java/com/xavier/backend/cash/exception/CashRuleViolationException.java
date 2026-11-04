package com.xavier.backend.cash.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;

/** A cash business rule refused the request: 422 with the stable {@code code} property (PRD R2 conventions). */
public class CashRuleViolationException extends BusinessRuleException {

    private final CashRule rule;

    public CashRuleViolationException(CashRule rule, String detail) {
        super(rule.name(), detail);
        this.rule = rule;
    }

    public CashRule rule() {
        return rule;
    }
}
