package com.xavier.backend.reporting.service;

/**
 * The sales report's groups (PRD S32) over {@code sales_record_line l}: the key and label expressions, and whether
 * the group needs the document header {@code s} (only the terminal is not on the line).
 */
enum SalesGrouping {
    DAY("l.business_date::text"),
    HOUR("lpad(l.local_hour::text, 2, '0')"),
    TERMINAL("s.terminal_id::text"),
    EMPLOYEE("l.served_by_employee_id::text"),
    AREA("l.area_code"),
    ITEM("l.item_code", "max(l.description)"),
    FAMILY("l.family_code"),
    MODE("l.mode");

    final String key;
    final String label;

    SalesGrouping(String key) {
        this(key, "NULL::text");
    }

    SalesGrouping(String key, String label) {
        this.key = key;
        this.label = label;
    }

    boolean needsHeader() {
        return this == TERMINAL;
    }
}
