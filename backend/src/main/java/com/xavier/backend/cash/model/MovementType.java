package com.xavier.backend.cash.model;

import java.util.Set;

/** What moved money in or out of a session (D §4). {@link #PAYMENT_RECEIPT} and {@link #REFUND} come from settlement. */
public enum MovementType {
    OPENING_FLOAT(MovementDirection.IN),
    PAYMENT_RECEIPT(MovementDirection.IN),
    CASH_IN(MovementDirection.IN),
    CASH_OUT(MovementDirection.OUT),
    EXPENSE(MovementDirection.OUT),
    /** Money paid back for a settlement Refund (A23). */
    REFUND(MovementDirection.OUT),
    /** A later correction on a CLOSED session (D79); its direction is chosen per movement, so it has none here. */
    ADJUSTMENT(null);

    /** The types an operator records by hand at the POS; always in cash, always with a reason. */
    public static final Set<MovementType> MANUAL = Set.of(CASH_IN, CASH_OUT, EXPENSE);

    private final MovementDirection direction;

    MovementType(MovementDirection direction) {
        this.direction = direction;
    }

    public MovementDirection direction() {
        return direction;
    }
}
