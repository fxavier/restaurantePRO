package com.xavier.backend.catalog.exception;

import com.xavier.backend.shared.exception.ConflictException;

/** A code that must be unique (item code, barcode, …) is already used, also when two requests race. 409. */
public class CatalogCodeTakenException extends ConflictException {

    public CatalogCodeTakenException(String what) {
        super(what + " is already used.");
    }
}
