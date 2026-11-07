package com.xavier.backend.inventory.model;

import java.math.BigDecimal;

/**
 * The type of a stock movement (D113) and the sign its quantity must have, as the {@code stock_movement_sign_ck}
 * CHECK enforces: INs are positive, OUTs negative, adjustments either, a revaluation moves value only.
 */
public enum MovementType {
    OPENING(Direction.IN),
    RECEIPT(Direction.IN),
    SUPPLIER_RETURN(Direction.OUT),
    SALE_CONSUMPTION(Direction.OUT),
    CONSUMPTION_REVERSAL(Direction.IN),
    WASTE(Direction.OUT),
    RETURN_TO_STOCK(Direction.IN),
    PREPARATION_OUT(Direction.OUT),
    PREPARATION_IN(Direction.IN),
    TRANSFER_OUT(Direction.OUT),
    TRANSFER_IN(Direction.IN),
    TRANSFER_SHORTAGE(Direction.OUT),
    COUNT_ADJUSTMENT(Direction.EITHER),
    MANUAL_ADJUSTMENT(Direction.EITHER),
    REVALUATION(Direction.NONE);

    private final Direction direction;

    MovementType(Direction direction) {
        this.direction = direction;
    }

    public boolean accepts(BigDecimal quantity) {
        return switch (direction) {
            case IN -> quantity.signum() > 0;
            case OUT -> quantity.signum() < 0;
            case EITHER -> quantity.signum() != 0;
            case NONE -> quantity.signum() == 0;
        };
    }

    private enum Direction {
        IN,
        OUT,
        EITHER,
        NONE
    }
}
