package com.xavier.backend.promotions.loyalty.exception;

/** The stable {@code code} of each loyalty business rule (422). Never renamed once released. */
public enum LoyaltyRule {
    LOYALTY_NOT_MEMBER,
    LOYALTY_ACCOUNT_SUSPENDED,
    LOYALTY_INSUFFICIENT_POINTS,
    LOYALTY_REWARD_NOT_APPLICABLE,
    LOYALTY_PROGRAM_INVALID,
    REASON_REQUIRED
}
