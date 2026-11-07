package com.xavier.backend.inventory.count.exception;

/** The stable {@code code} of each count business rule (422). Never renamed once released. */
public enum CountRule {
    OPENING_NOT_FIRST,
    COUNT_INCOMPLETE,
    COUNTED_AT_OUT_OF_RANGE,
    UNIT_COST_OPENING_ONLY,
    COUNT_SCOPE_INVALID,
    SELF_APPROVAL
}
