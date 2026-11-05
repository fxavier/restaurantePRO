package com.xavier.backend.production.exception;

import com.xavier.backend.shared.exception.ConflictException;

/** A production duplicate decided by a unique key (409 with a stable {@code code}). */
public class ProductionConflictException extends ConflictException {

    private ProductionConflictException(String code, String message) {
        super(code, message);
    }

    /** {@code printer_store_code_uk}. */
    public static ProductionConflictException printerCodeTaken(String code) {
        return new ProductionConflictException("PRINTER_CODE_TAKEN",
                "A printer with code " + code + " already exists in this store");
    }

    /** {@code terminal_printer_pk}: the terminal is assigned in another store. */
    public static ProductionConflictException terminalAssignedElsewhere() {
        return new ProductionConflictException("TERMINAL_ASSIGNED_ELSEWHERE",
                "A terminal of this table already has a printer in another store");
    }

    /** {@code production_ticket_source_uk} lost to a concurrent delivery of the same source. */
    public static ProductionConflictException ticketExists(Object sourceType, Object sourceId) {
        return new ProductionConflictException("TICKET_EXISTS",
                "A ticket for " + sourceType + " " + sourceId + " was created concurrently");
    }
}
