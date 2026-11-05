package com.xavier.backend.production.exception;

import com.xavier.backend.shared.exception.ConflictException;
import java.util.UUID;

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

    /** {@code destination_route_pk}: another replacement of the store's routes committed first; re-read, retry. */
    public static ProductionConflictException routesChangedConcurrently() {
        return new ProductionConflictException("ROUTES_CHANGED_CONCURRENTLY",
                "The store's routes were changed at the same time; read them again and retry");
    }

    /** The ticket is ROUTED already: routed earlier, or by a concurrent request that committed first (D89 (1)). */
    public static ProductionConflictException ticketAlreadyRouted(UUID ticketId) {
        return new ProductionConflictException("TICKET_ALREADY_ROUTED",
                "Production ticket " + ticketId + " is already routed");
    }

    /** {@code production_ticket_source_uk} lost to a concurrent delivery of the same source. */
    public static ProductionConflictException ticketExists(Object sourceType, Object sourceId) {
        return new ProductionConflictException("TICKET_EXISTS",
                "A ticket for " + sourceType + " " + sourceId + " was created concurrently");
    }
}
