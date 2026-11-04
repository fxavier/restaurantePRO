package com.xavier.backend.dining.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * A table, or an area with a table, that has an open occupation cannot be deactivated ({@code OCCUPIED}): close or
 * move the order first. 422.
 */
public class StillOccupiedException extends DiningProblemException {

    public StillOccupiedException(String entityType, UUID id) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, "OCCUPIED",
                entityType + " " + id + " has an open table occupation and cannot be deactivated.");
    }
}
