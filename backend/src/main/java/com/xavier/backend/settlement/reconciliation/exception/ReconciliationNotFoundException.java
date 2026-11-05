package com.xavier.backend.settlement.reconciliation.exception;

import com.xavier.backend.shared.exception.NotFoundException;
import java.util.UUID;

/** A store, statement batch or entry that does not exist (404). */
public class ReconciliationNotFoundException extends NotFoundException {

    public ReconciliationNotFoundException(String entityType, UUID id) {
        super(entityType + " " + id + " was not found.");
    }
}
