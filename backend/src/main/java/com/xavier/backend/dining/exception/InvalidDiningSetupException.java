package com.xavier.backend.dining.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;

/** A room area or table value is not acceptable (blank code, no seats). 422. */
public class InvalidDiningSetupException extends BusinessRuleException {

    public InvalidDiningSetupException(String message) {
        super(message);
    }
}
