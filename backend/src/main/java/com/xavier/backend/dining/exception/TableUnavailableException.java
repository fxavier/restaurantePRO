package com.xavier.backend.dining.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * The table cannot take an occupation (r2-decisions §4 dining): it is in another store than the session
 * ({@code STORE_MISMATCH}), it or its area is inactive ({@code TABLE_INACTIVE}), or it is locked
 * ({@code TABLE_LOCKED}). 422.
 */
public class TableUnavailableException extends DiningProblemException {

    private TableUnavailableException(String code, String detail) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, code, detail);
    }

    public static TableUnavailableException storeMismatch(UUID tableId) {
        return new TableUnavailableException("STORE_MISMATCH", "Table " + tableId + " is not in this store.");
    }

    public static TableUnavailableException inactive(UUID tableId) {
        return new TableUnavailableException("TABLE_INACTIVE", "Table " + tableId + " or its area is inactive.");
    }

    public static TableUnavailableException locked(UUID tableId) {
        return new TableUnavailableException("TABLE_LOCKED", "Table " + tableId + " is locked.");
    }
}
