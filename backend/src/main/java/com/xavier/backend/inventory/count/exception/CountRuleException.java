package com.xavier.backend.inventory.count.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;
import java.time.Instant;
import java.util.UUID;

/** A count business rule refused the request: 422 with the stable {@code code} (PRD R4 conventions). */
public class CountRuleException extends BusinessRuleException {

    private final CountRule rule;

    private CountRuleException(CountRule rule, String detail) {
        super(rule.name(), detail);
        this.rule = rule;
    }

    /** D122: an opening is the first fact of its warehouse, and there is one. */
    public static CountRuleException openingNotFirst(UUID warehouseId) {
        return new CountRuleException(CountRule.OPENING_NOT_FIRST, "Warehouse " + warehouseId
                + " already has stock movements or an opening count before this opening's cut-off.");
    }

    public static CountRuleException incomplete(UUID countId) {
        return new CountRuleException(CountRule.COUNT_INCOMPLETE,
                "Count " + countId + " has no line, or a line not counted yet.");
    }

    public static CountRuleException countedAtInFuture(Instant countedAt) {
        return new CountRuleException(CountRule.COUNTED_AT_IN_FUTURE,
                "A line cannot be counted in the future (" + countedAt + ").");
    }

    public static CountRuleException unitCostOpeningOnly() {
        return new CountRuleException(CountRule.UNIT_COST_OPENING_ONLY,
                "A unit cost is entered on an opening count only: a regular count is valued at the average.");
    }

    public static CountRuleException scopeInvalid(String detail) {
        return new CountRuleException(CountRule.COUNT_SCOPE_INVALID, detail);
    }

    public CountRule rule() {
        return rule;
    }
}
