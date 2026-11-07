package com.xavier.backend.catalog.model;

/** When an automatic item is added as a draft (D109). */
public enum AutomaticTrigger {
    /** Quantity × guests when a table opens (a cover charge). */
    TABLE_OPEN_PER_GUEST,
    /** When an order of the item's mode (or of any mode) opens. */
    ORDER_OPEN
}
