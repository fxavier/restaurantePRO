package com.xavier.backend.dining.exception;

import com.xavier.backend.shared.exception.ConflictException;

/** Area codes and table codes are each unique within a store. 409. */
public class DiningCodeTakenException extends ConflictException {

    public DiningCodeTakenException(String entityType, String code) {
        super(entityType + " code '" + code + "' is already used in this store.");
    }
}
