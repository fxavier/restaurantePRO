package com.xavier.backend.reporting.service;

/**
 * The discounts report's groups (D131, §8.6): the key over {@code discount_record d} (what operators did) and over
 * {@code offer_cost_record o} (the cost of offers, measured at close; its origin is always OFFER).
 */
enum DiscountGrouping {
    ORIGIN("d.origin", "'OFFER'::text"),
    PROMOTION("d.promotion_code", "o.promotion_code"),
    EMPLOYEE("d.operator_employee_id::text", "o.operator_employee_id::text"),
    APPROVER("d.approver_employee_id::text", "o.approver_employee_id::text"),
    ITEM("d.item_id::text", "o.item_id::text");

    final String key;
    final String offerKey;

    DiscountGrouping(String key, String offerKey) {
        this.key = key;
        this.offerKey = offerKey;
    }
}
