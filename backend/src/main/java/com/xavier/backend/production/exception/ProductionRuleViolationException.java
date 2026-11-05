package com.xavier.backend.production.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;

/** A production business rule refused the request: 422 with the stable {@code code} property. */
public class ProductionRuleViolationException extends BusinessRuleException {

    private final ProductionRule rule;

    public ProductionRuleViolationException(ProductionRule rule, String detail) {
        super(rule.name(), detail);
        this.rule = rule;
    }

    public ProductionRule rule() {
        return rule;
    }
}
