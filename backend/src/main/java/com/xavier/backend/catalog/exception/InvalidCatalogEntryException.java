package com.xavier.backend.catalog.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;

/** A well-formed catalog edit breaks a catalog rule (unknown reference, subfamily depth, bad amount…). 422. */
public class InvalidCatalogEntryException extends BusinessRuleException {

    public InvalidCatalogEntryException(String message) {
        super(message);
    }
}
