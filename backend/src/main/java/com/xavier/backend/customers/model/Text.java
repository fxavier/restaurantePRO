package com.xavier.backend.customers.model;

/** Optional free text from a request: stripped, and blank means absent. */
final class Text {

    private Text() {
    }

    static String optional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.strip();
    }
}
