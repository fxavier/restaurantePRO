package com.xavier.backend.organization.exception;

import com.xavier.backend.shared.exception.ConflictException;

/** Store codes are unique in the installation (spec §8.2). 409 with code {@value #CODE}. */
public class StoreCodeTakenException extends ConflictException {

    public static final String CODE = "STORE_CODE_TAKEN";

    public StoreCodeTakenException(String code) {
        super(CODE, "Store code '" + code + "' is already used.");
    }
}
