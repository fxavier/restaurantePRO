package com.xavier.backend.production.model;

/**
 * How a production ticket's content is stored (D155): PLAIN text lines (R4 tickets and fiscal printouts, which keep
 * their adapter's layout) or STYLED_V1 JSON lines (production's own layouts).
 */
public enum ContentFormat {
    PLAIN,
    STYLED_V1
}
