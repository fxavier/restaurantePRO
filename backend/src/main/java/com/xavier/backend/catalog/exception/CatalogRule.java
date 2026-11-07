package com.xavier.backend.catalog.exception;

/** The coded catalog rules of R4 (PRD S38); the name is the ProblemDetail {@code code}. */
public enum CatalogRule {
    /** A menu group is malformed or names options a menu cannot have (D108). */
    MENU_GROUP_INVALID,
    /** A macro entry or an automatic item names a MENU item, which would add a menu draft without choices. */
    MENU_NOT_ALLOWED,
    /** A daily dish is not in the store's published release (D109). */
    ITEM_NOT_PUBLISHED
}
