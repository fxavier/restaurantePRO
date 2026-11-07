package com.xavier.backend.orders.model;

/**
 * What a line sells (D108, P50): a STANDARD item, a MENU (billed once at the menu price; its chosen components are
 * unpriced child records) or a FEE (a delivery fee, a cover charge). Copied from the catalog item at confirmation.
 */
public enum LineKind {
    STANDARD,
    MENU,
    FEE
}
