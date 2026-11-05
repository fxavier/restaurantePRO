package com.xavier.backend.reporting.service;

/** The voids report's groups over {@code void_record}: who voided ({@code employee}) or approved it, and why. */
enum VoidsGrouping {
    REASON("reason", "NULL::text"),
    EMPLOYEE("employee_id::text", "NULL::text"),
    ITEM("item_code", "max(description)"),
    APPROVER("approver_id::text", "NULL::text");

    final String key;
    final String label;

    VoidsGrouping(String key, String label) {
        this.key = key;
        this.label = label;
    }
}
