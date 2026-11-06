package com.xavier.backend.settlement.model;

/**
 * How a payment method collects (D52, P10). {@code CASH}: the drawer, with change. {@code EXTERNAL_TERMINAL}: a card or
 * mobile-money terminal not connected to us, whose result the operator confirms. {@code INTEGRATED}: a
 * {@link com.xavier.backend.settlement.PaymentProvider} charges it (D53).
 */
public enum PaymentMethodKind {
    CASH,
    EXTERNAL_TERMINAL,
    INTEGRATED
}
