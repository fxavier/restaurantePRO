package com.xavier.backend.organization.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;

/** A store value is not acceptable (e.g. an unknown timezone). 422. */
public class InvalidStoreException extends BusinessRuleException {

    public InvalidStoreException(String message) {
        super(message);
    }
}
