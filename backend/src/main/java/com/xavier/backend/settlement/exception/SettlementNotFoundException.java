package com.xavier.backend.settlement.exception;

import com.xavier.backend.shared.exception.NotFoundException;
import java.util.UUID;

/** A payment attempt or payment method that does not exist (404). */
public class SettlementNotFoundException extends NotFoundException {

    public SettlementNotFoundException(String entityType, UUID id) {
        super(entityType + " " + id + " was not found.");
    }
}
