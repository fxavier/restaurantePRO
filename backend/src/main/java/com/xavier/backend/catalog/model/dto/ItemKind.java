package com.xavier.backend.catalog.model.dto;

/**
 * What an item is when sold (D108, P48). {@code STANDARD}: a plain product. {@code MENU}: one billed line at its own
 * price whose chosen components (its menu groups) carry no amount. {@code FEE}: a charge such as a delivery fee,
 * never a menu.
 */
public enum ItemKind {
    STANDARD,
    MENU,
    FEE
}
