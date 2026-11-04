package com.xavier.backend.dining.exception;

import com.xavier.backend.shared.exception.NotFoundException;
import java.util.UUID;

/** The store, area or table addressed by the request does not exist. 404. */
public class DiningNotFoundException extends NotFoundException {

    public DiningNotFoundException(String entityType, UUID id) {
        super(entityType + " " + id + " was not found.");
    }
}
