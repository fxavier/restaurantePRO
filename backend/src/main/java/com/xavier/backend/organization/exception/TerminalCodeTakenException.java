package com.xavier.backend.organization.exception;

import com.xavier.backend.shared.exception.ConflictException;

/** Terminal codes are unique within a store (spec §8.2). 409. */
public class TerminalCodeTakenException extends ConflictException {

    public TerminalCodeTakenException(String code) {
        super("Terminal code '" + code + "' is already used in this store.");
    }
}
