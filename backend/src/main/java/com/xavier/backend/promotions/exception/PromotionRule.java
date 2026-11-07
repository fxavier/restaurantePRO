package com.xavier.backend.promotions.exception;

/** The stable codes of the promotions 422 refusals (PRD R4 S39); a code is never renamed once released. */
public enum PromotionRule {
    /** Kind values, an empty scope, a validity that ends before it starts, an unknown store, a bad window. */
    PROMOTION_INVALID,
    /** Free limit outside 0–100, or a maximum below it or above 100. */
    DISCOUNT_LIMITS_INVALID,
    /** The working copy cannot be validated or published; {@code errors} lists every problem. */
    PROMOTION_RELEASE_INVALID
}
