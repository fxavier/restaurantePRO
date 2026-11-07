package com.xavier.backend.reporting.service;

/** The discounts report's groups (D131, §8.6) over {@code discount_record d}. */
enum DiscountGrouping {
    ORIGIN("d.origin"),
    PROMOTION("d.promotion_code"),
    EMPLOYEE("d.operator_employee_id::text"),
    APPROVER("d.approver_employee_id::text"),
    ITEM("d.item_id::text");

    final String key;

    DiscountGrouping(String key) {
        this.key = key;
    }
}
