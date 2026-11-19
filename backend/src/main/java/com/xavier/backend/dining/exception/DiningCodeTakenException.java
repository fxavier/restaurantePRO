package com.xavier.backend.dining.exception;

import com.xavier.backend.shared.exception.ConflictException;

/** Area codes and table codes are each unique within a store. 409 with code {@value #CODE}. */
public class DiningCodeTakenException extends ConflictException {

    public static final String CODE = "DINING_CODE_TAKEN";

    public DiningCodeTakenException(String entityType, String code) {
        super(CODE, entityType + " code '" + code + "' is already used in this store.");
    }
}
