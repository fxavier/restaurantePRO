package com.xavier.backend.cash.exception;

import com.xavier.backend.shared.exception.ConflictException;

/**
 * The register, or the operator, already has an OPEN session (P16: one per register and per operator). 409 with code
 * {@value #CODE}.
 */
public class CashSessionAlreadyOpenException extends ConflictException {

    public static final String CODE = "CASH_SESSION_ALREADY_OPEN";

    public CashSessionAlreadyOpenException() {
        super(CODE, "The register or the operator already has an open cash session.");
    }
}
