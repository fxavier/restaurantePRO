package com.xavier.backend.dining.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;
import java.util.UUID;

/**
 * A table, or an area with a table, that has an open occupation cannot be deactivated ({@code OCCUPIED}): close or
 * move the order first. 422.
 */
public class StillOccupiedException extends BusinessRuleException {

    public StillOccupiedException(String entityType, UUID id) {
        super("OCCUPIED",
                entityType + " " + id + " has an open table occupation and cannot be deactivated.");
    }
}
