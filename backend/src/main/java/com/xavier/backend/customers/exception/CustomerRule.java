package com.xavier.backend.customers.exception;

/** The stable {@code code} of each customers business rule (422). Never renamed once released. */
public enum CustomerRule {
    CUSTOMER_INACTIVE,
    CREDIT_ACCOUNT_MISSING,
    CREDIT_BLOCKED,
    CREDIT_LIMIT_EXCEEDED,
    AMOUNT_EXCEEDS_DEBT,
    AMOUNT_EXCEEDS_CREDIT,
    INVALID_AMOUNT
}
