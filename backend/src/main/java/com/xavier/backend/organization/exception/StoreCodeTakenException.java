package com.xavier.backend.organization.exception;

import com.xavier.backend.shared.exception.ConflictException;

/** Store codes are unique in the installation (spec §8.2). 409. */
public class StoreCodeTakenException extends ConflictException {

    public StoreCodeTakenException(String code) {
        super("Store code '" + code + "' is already used.");
    }
}
