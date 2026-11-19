package com.xavier.backend.organization.exception;

import com.xavier.backend.shared.exception.ConflictException;

/** Terminal codes are unique within a store (spec §8.2). 409 with code {@value #CODE}. */
public class TerminalCodeTakenException extends ConflictException {

    public static final String CODE = "TERMINAL_CODE_TAKEN";

    public TerminalCodeTakenException(String code) {
        super(CODE, "Terminal code '" + code + "' is already used in this store.");
    }
}
