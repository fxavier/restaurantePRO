package com.xavier.backend.dining.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * The table already has an open occupation ({@code TABLE_OCCUPIED}), or the order already sits at a table
 * ({@code ORDER_SEATED}). Decided by the partial unique indexes when two requests race. 409.
 */
public class TableOccupiedException extends DiningProblemException {

    private TableOccupiedException(String code, String detail) {
        super(HttpStatus.CONFLICT, code, detail);
    }

    public static TableOccupiedException table(UUID tableId) {
        return new TableOccupiedException("TABLE_OCCUPIED", "Table " + tableId + " is occupied.");
    }

    public static TableOccupiedException order(UUID orderId) {
        return new TableOccupiedException("ORDER_SEATED", "Order " + orderId + " already occupies a table.");
    }
}
