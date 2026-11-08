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

    /** A line's cut-off lies between the count's start and now (D119 amendment). */
    public static CountRuleException countedAtOutOfRange(Instant countedAt, Instant startedAt, Instant now) {
        return new CountRuleException(CountRule.COUNTED_AT_OUT_OF_RANGE, "A line is counted between the count's "
                + "start (" + startedAt + ") and now (" + now + "), not at " + countedAt + ".");
    }

    /** The approver counted a line of this count: approval is someone else's (§8.5 segregation, shared code). */
    public static CountRuleException selfApproval(UUID countId) {
        return new CountRuleException(CountRule.SELF_APPROVAL,
                "Count " + countId + " was counted (in part) by its approver: another person approves it.");
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
