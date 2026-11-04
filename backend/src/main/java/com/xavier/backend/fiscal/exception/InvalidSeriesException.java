package com.xavier.backend.fiscal.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;

/** A series definition breaks a rule (blank code, first number below 1). 422. */
public class InvalidSeriesException extends BusinessRuleException {

    public InvalidSeriesException(String message) {
        super(message);
    }
}
