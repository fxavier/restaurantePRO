package com.xavier.backend.settlement.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;
import java.util.Map;

/** A settlement business rule refused the request: 422 with the stable {@code code} property. */
public class SettlementRuleException extends BusinessRuleException {

    private final SettlementRule rule;

    public SettlementRuleException(SettlementRule rule, String detail) {
        super(rule.name(), detail);
        this.rule = rule;
    }

    public SettlementRuleException(SettlementRule rule, String detail, Map<String, ?> properties) {
        super(rule.name(), detail, properties);
        this.rule = rule;
    }

    public static SettlementRuleException noOpenSession() {
        return new SettlementRuleException(SettlementRule.NO_OPEN_CASH_SESSION,
                "You have no open cash session in this store: open one before collecting.");
    }

    public SettlementRule rule() {
        return rule;
    }
}
