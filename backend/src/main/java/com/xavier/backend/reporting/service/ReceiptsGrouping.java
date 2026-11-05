package com.xavier.backend.reporting.service;

/** The receipts report's groups (PRD S32) and the {@code payment_record} column each one groups by. */
enum ReceiptsGrouping {
    METHOD("method_code"),
    EMPLOYEE("employee_id::text"),
    DAY("business_date::text");

    final String key;

    ReceiptsGrouping(String key) {
        this.key = key;
    }
}
