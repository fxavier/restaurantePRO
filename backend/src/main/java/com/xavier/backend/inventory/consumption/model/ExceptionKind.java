package com.xavier.backend.inventory.consumption.model;

/** Why a sold line or component could not be consumed (D116); the CHECK of {@code consumption_exception.kind}. */
public enum ExceptionKind {
    NO_RECIPE,
    NO_ROUTE,
    INACTIVE_STOCK_ITEM
}
