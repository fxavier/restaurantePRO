package com.xavier.backend.cash.exception;

import com.xavier.backend.shared.exception.ConflictException;

/** The register, or the operator, already has an OPEN session (P16: one per register and per operator). 409. */
public class CashSessionAlreadyOpenException extends ConflictException {

    public CashSessionAlreadyOpenException() {
        super("The register or the operator already has an open cash session.");
    }
}
