package com.xavier.backend.cash.model.dto;

import com.xavier.backend.cash.model.MovementType;

/** The movements an operator records by hand; anything else is not accepted from the request (400). */
public enum ManualMovementType {
    CASH_IN, CASH_OUT, EXPENSE;

    public MovementType toType() {
        return MovementType.valueOf(name());
    }
}
