package com.xavier.backend.catalog.exception;

import com.xavier.backend.shared.exception.ConflictException;

/**
 * A code that must be unique (item code, barcode, …) is already used, also when two requests race. 409 with code
 * {@value #CODE}.
 */
public class CatalogCodeTakenException extends ConflictException {

    public static final String CODE = "CATALOG_CODE_TAKEN";

    public CatalogCodeTakenException(String what) {
        super(CODE, what + " is already used.");
    }
}
